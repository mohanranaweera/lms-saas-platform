import { test, expect, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import { ADMIN_EMAIL, TENANT_BASE, api, apiLogin, createCourse, firstTeacherUserId, loginUi } from "./fixtures/klass/klass-api";

/**
 * STU-MGMT-02 live end-to-end check against the real local backend (opt-in:
 * `STU_LIVE=1`). Synthetic data only. Creates a student through the real form,
 * reopens it, uploads/removes a photo, exercises the drawer, full profile, QR,
 * Bulk Print QRs export and a real "Log as Student" support session, and captures
 * evidence into docs/qa/student-management-evidence/.
 */
test.skip(!process.env.STU_LIVE, "live backend check; set STU_LIVE=1");
test.describe.configure({ mode: "serial" });
test.setTimeout(180_000);

const EVIDENCE = path.resolve(__dirname, "../../docs/qa/student-management-evidence");
const tag = Date.now().toString(36);
let token = "";
let course = { id: "", name: "" };
let studentId = "";
const first = `Nadee${tag}`;
const digits = (n: number) => Array.from({ length: n }, () => Math.floor(Math.random() * 10)).join("");
const last = "Perera";

/** A 400×400 RGB PNG (gradient) built without dependencies. */
function samplePng(size = 400): Buffer {
  const raw = Buffer.alloc((size * 3 + 1) * size);
  for (let y = 0; y < size; y++) {
    raw[y * (size * 3 + 1)] = 0;
    for (let x = 0; x < size; x++) {
      const o = y * (size * 3 + 1) + 1 + x * 3;
      raw[o] = (x * 255) / size; raw[o + 1] = 120; raw[o + 2] = (y * 255) / size;
    }
  }
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (b: Buffer) => { let c = 0xffffffff; for (const v of b) c = crcTable[(c ^ v) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(size, 0); ihdr.writeUInt32BE(size, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)), chunk("IEND", Buffer.alloc(0))]);
}

/** Form control by accessible name (required labels render "Name *"). */
function input(scope: ReturnType<Page["getByRole"]>, label: string) {
  return scope.getByRole("textbox", { name: label, exact: true })
    .or(scope.getByRole("spinbutton", { name: label, exact: true }))
    .or(scope.getByLabel(label, { exact: true }))
    .first();
}

async function shot(page: Page, name: string, settleMs = 300) {
  await page.waitForTimeout(settleMs);
  fs.mkdirSync(EVIDENCE, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE, `${name}.png`), fullPage: false });
}

async function noHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, "horizontal page overflow").toBeLessThanOrEqual(1);
}

test.beforeAll(async ({ request }) => {
  token = await apiLogin(request, ADMIN_EMAIL);
  const teacher = await firstTeacherUserId(request, token);
  course = await createCourse(request, token, { name: `STU Parity Course ${tag}`, teacherIds: [teacher], pricingModel: "FREE" });
  // A second synthetic student for the grid / bulk QR (API path, blank DOB).
  const res = await api(request, token, "POST", "/students/v2", {
    email: `stu-api-${tag}@example.test`, firstName: `Kasun${tag}`, lastName: "Silva", mobile: `0771${digits(6)}`,
    dateOfBirth: null, courseIds: [course.id],
  });
  expect(res.status, JSON.stringify(res.body)).toBe(201);
});

test("create through the real form, reopen, and every field round-trips", async ({ page, request }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/students`);
  await expect(page.getByRole("heading", { name: "Students", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Add Student" }).click();
  const sheet = page.getByRole("dialog").last();
  const mobile = `0771${digits(6)}`;
  const values: Record<string, string> = {
    Email: `stu-ui-${tag}@example.test`, "First Name": first, "Last Name": last, "WhatsApp Number": "0712345678",
    "Date of Birth": "2007-03-09", "Mobile Number": mobile, "Address Line 1": "12 Temple Road", "Address Line 2": "Pitakotte",
    City: "Kotte", "Postal Code": "10100", "Name of the Guardian": "Sunil Perera", "Contact Number of the Guardian": "0719998887",
    "NIC of Guardian": "751234567V", "Home Telephone": "0112345678", School: "Royal College", "A/L Year": "2026", "A/L Stream": "Physical Science",
  };
  for (const [label, value] of Object.entries(values)) await input(sheet, label).fill(value);
  await sheet.getByRole("checkbox", { name: course.name }).check();
  await shot(page, "create-student-form");
  const created = page.waitForResponse((r) => r.url().endsWith("/students/v2") && r.request().method() === "POST");
  await sheet.getByRole("button", { name: "Create Student" }).click();
  const response = await created;
  expect(response.status()).toBe(201);
  const body = await response.json();
  studentId = body.data.student.id;
  expect(body.data.temporaryPassword, "blank password → one-time temporary password").toBeTruthy();
  await expect(page.getByTestId("temporary-password")).toBeVisible();
  await shot(page, "create-student-temporary-password");

  const detail = await api<Record<string, unknown>>(request, token, "GET", `/students/${studentId}/detail`);
  const d = detail.body.data!;
  expect(d).toMatchObject({
    firstName: first, lastName: last, mobile, whatsappNumber: "0712345678", dateOfBirth: "2007-03-09", addressLine: "12 Temple Road",
    addressLine2: "Pitakotte", city: "Kotte", postalCode: "10100", guardianName: "Sunil Perera", guardianPhone: "0719998887",
    guardianNic: "751234567V", fixedPhone: "0112345678", school: "Royal College", alYear: 2026, stream: "Physical Science",
  });
  expect(String(d.indexNumber)).toMatch(/^\d{4,}$/);
  expect(d.enrolledCourseIds).toContain(course.id);
  // Free enrollment: no order was created for this student.
  const orders = await api<{ content?: unknown[] } | unknown[]>(request, token, "GET", `/admin/orders?search=${encodeURIComponent(first)}`);
  if (orders.status === 200) {
    const list = Array.isArray(orders.body.data) ? orders.body.data : (orders.body.data as { content?: unknown[] })?.content ?? [];
    expect(list.length).toBe(0);
  }
});

test("directory grid, search, edit sheet reopen, drawer and photo", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/students`);
  await expect(page.getByRole("table", { name: "Active students" })).toBeVisible();
  await shot(page, "directory-desktop");
  await page.getByLabel("Search", { exact: true }).fill(first);
  await expect(page.getByRole("table", { name: "Active students" }).getByRole("row")).toHaveCount(2, { timeout: 15_000 });
  await shot(page, "directory-search");
  await page.getByRole("button", { name: `More actions: ${first} ${last}` }).click().catch(async () => {
    await page.getByRole("button", { name: new RegExp(`More.*${first}`) }).click();
  });
  await shot(page, "directory-more-menu");
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: `Edit Student: ${first} ${last}` }).click();
  const sheet = page.getByRole("dialog").last();
  await expect(input(sheet, "School")).toHaveValue("Royal College");
  await expect(input(sheet, "Date of Birth")).toHaveValue("2007-03-09");
  await expect(sheet.getByRole("textbox", { name: "Password", exact: true })).toHaveCount(0);
  await sheet.getByLabel("Upload photo").setInputFiles({ name: "avatar.png", mimeType: "image/png", buffer: samplePng() });
  await shot(page, "profile-photo-crop");
  const saved = page.waitForResponse((r) => r.url().endsWith(`/students/${studentId}/photo`) && r.request().method() === "POST");
  await sheet.getByRole("button", { name: "Save photo" }).click();
  expect((await saved).status()).toBe(200);
  await shot(page, "edit-student-with-photo");
  await page.keyboard.press("Escape");

  await page.getByRole("button", { name: `View Student Details: ${first} ${last}` }).click();
  const drawer = page.getByTestId("student-details-drawer");
  await expect(drawer.getByTestId("drawer-student-name")).toContainText(first);
  await expect(drawer.getByRole("img", { name: `${first} ${last} profile photo` })).toBeVisible();
  await expect(drawer.getByTestId("drawer-courses")).toContainText(course.name);
  await shot(page, "details-drawer");
});

test("full profile with QR, hard refresh keeps the photo, responsive layouts", async ({ page, request }) => {
  await loginUi(page, ADMIN_EMAIL);
  for (const [name, width, height] of [["desktop", 1440, 900], ["tablet", 834, 1112], ["mobile", 390, 844]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${TENANT_BASE}/tenant-admin/students/${studentId}`);
    await expect(page.getByTestId("profile-student-name")).toContainText(first, { timeout: 30_000 });
    await expect(page.getByTestId("student-qr-image")).toBeVisible();
    await expect(page.getByRole("img", { name: `${first} ${last} profile photo` }).first()).toBeVisible();
    await noHorizontalOverflow(page);
    await shot(page, `full-profile-${name}`, 800);
    if (name === "desktop") {
      await page.getByTestId("student-qr-card").screenshot({ path: path.join(EVIDENCE, "full-profile-qr-card.png") });
    }
  }
  for (const [name, width, height] of [["tablet", 834, 1112], ["mobile", 390, 844]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${TENANT_BASE}/tenant-admin/students`);
    await expect(page.getByRole("heading", { name: "Students", exact: true })).toBeVisible();
    await noHorizontalOverflow(page);
    await shot(page, `directory-${name}`, 800);
  }
  // Individual QR PNG straight from the API (decoded by the backend test suite too).
  const png = await request.get(`${TENANT_BASE.replace(":3000", ":8080")}/api/v1/students/${studentId}/qr.png`, {
    headers: { Authorization: `Bearer ${token}`, Host: new URL(TENANT_BASE).host },
  }).catch(() => null);
  if (png && png.ok()) fs.writeFileSync(path.join(EVIDENCE, "student-qr.png"), await png.body());

  // Removal falls back to initials.
  const removed = await api(request, token, "DELETE", `/students/${studentId}/photo`);
  expect([200, 204]).toContain(removed.status);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${TENANT_BASE}/tenant-admin/students/${studentId}`);
  await expect(page.getByTestId("profile-student-name")).toContainText(first, { timeout: 30_000 });
  await expect(page.getByRole("img", { name: `${first} ${last} profile photo` })).toHaveCount(0);
  await shot(page, "full-profile-photo-removed", 800);
});

test("Bulk Print QRs exports exactly the selected students", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/students`);
  await page.getByRole("button", { name: "Export QRs" }).click();
  const dialog = page.getByTestId("bulk-qr-dialog");
  await dialog.getByLabel("Search Students").fill(tag);
  await expect(dialog.getByTestId("bulk-qr-row")).toHaveCount(2, { timeout: 15_000 });
  await dialog.getByRole("checkbox", { name: new RegExp(`^Select all students matching`) }).click();
  await expect(dialog.getByTestId("bulk-qr-selected-count")).toHaveText("2 selected");
  await shot(page, "bulk-qr-dialog");
  const exportRequest = page.waitForRequest((r) => r.url().endsWith("/students/qr-export"));
  const download = page.waitForEvent("download");
  await dialog.getByRole("button", { name: "Export" }).click();
  expect((await exportRequest).postDataJSON().studentIds).toHaveLength(2);
  await (await download).saveAs(path.join(EVIDENCE, "bulk-qr-labels.pdf"));
});

test("Log as Student: real support session, read-only, banner and exit", async ({ page, context }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginUi(page, ADMIN_EMAIL);
  await page.goto(`${TENANT_BASE}/tenant-admin/students`);
  await page.getByLabel("Search", { exact: true }).fill(first);
  await page.getByRole("button", { name: `Login As Student: ${first} ${last}` }).click();
  const dialog = page.getByTestId("log-as-student-dialog");
  await dialog.getByLabel("Reason").fill("Live verification of the student view (synthetic)");
  await shot(page, "log-as-student-dialog");
  const popupPromise = context.waitForEvent("page");
  await dialog.getByRole("button", { name: "Start support session" }).click();
  const popup = await popupPromise;
  await popup.setViewportSize({ width: 1440, height: 900 });
  await expect(popup).toHaveURL(/\/student\/dashboard$/, { timeout: 60_000 });
  const banner = popup.getByTestId("impersonation-banner");
  await expect(banner).toContainText(`Viewing as ${first} ${last}`);
  await shot(popup, "impersonation-banner", 1500);

  // No bearer credential persisted in web storage or the URL.
  const storage = await popup.evaluate(() => JSON.stringify({ ...window.localStorage, ...window.sessionStorage }));
  expect(storage).not.toMatch(/eyJ[A-Za-z0-9_-]{10,}\./);
  expect(popup.url()).not.toMatch(/token|eyJ/);

  // Read-only: a write from the support tab is refused by the server.
  const write = await popup.evaluate(async () => {
    const r = await fetch("/api/v1/students/me/billing-address", { method: "PUT" }).catch(() => null);
    return r?.status ?? -1;
  });
  expect([401, 403, 404, -1]).toContain(write);

  // Reload in the support tab re-acquires via the impersonation endpoint and stays the student.
  await popup.reload();
  await expect(popup.getByTestId("impersonation-banner")).toContainText(first, { timeout: 60_000 });

  await banner.getByRole("button", { name: "Exit to admin" }).click();
  await expect(popup).toHaveURL(/\/tenant-admin\/students/, { timeout: 60_000 });
  await expect(popup.getByTestId("impersonation-banner")).toHaveCount(0);
  await shot(popup, "impersonation-exit-admin", 1000);
  // The admin's original tab is still the admin.
  await page.reload();
  await expect(page.getByRole("heading", { name: "Students", exact: true })).toBeVisible();
});

test("Today's Classes on the public home (CAL-01 reuse)", async ({ page }) => {
  for (const [name, width, height] of [["desktop", 1440, 900], ["mobile", 390, 844]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${TENANT_BASE}/`);
    const section = page.getByRole("region", { name: /Today's Classes/i }).or(page.getByText(/Today's Classes/i).first());
    await expect(section.first()).toBeVisible({ timeout: 30_000 });
    await noHorizontalOverflow(page);
    await shot(page, `home-todays-classes-${name}`, 800);
  }
});
