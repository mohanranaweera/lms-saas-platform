import { test, expect, type BrowserContext, type Page, type Route, type Request } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * STU-MGMT-02 (Klass student-management parity, docs/specs/student-management-parity.md):
 * directory, create/edit sheet, details drawer, full profile, photo editor,
 * Bulk Print QRs and "Log as Student" (ADR-033). Every backend call is mocked at
 * the browser-context level (so tabs opened with window.open share the mocks);
 * any unmocked `/api/v1/**` call gets a 404 envelope.
 */

test.beforeEach(() => {
  test.slow();
});

const NAV_TIMEOUT = 30_000;

interface Row {
  id: string;
  indexNumber: string;
  firstName: string;
  lastName: string;
  email: string;
  mobile: string;
  lastLoginAt: string | null;
  status: string;
  hasPhoto: boolean;
}

const FIRST = ["Ada", "Grace", "Alan", "Katherine", "Linus", "Margaret", "Dennis", "Barbara", "Edsger", "Frances"];
const LAST = ["Lovelace", "Hopper", "Turing", "Johnson", "Torvalds", "Hamilton", "Ritchie", "Liskov", "Dijkstra", "Allen"];

const ACTIVE: Row[] = Array.from({ length: 25 }, (_, i) => ({
  id: `stu-${String(i + 1).padStart(2, "0")}`,
  indexNumber: String(i + 1).padStart(4, "0"),
  firstName: FIRST[i % 10],
  lastName: `${LAST[i % 10]}${i >= 10 ? ` ${Math.floor(i / 10)}` : ""}`,
  email: `student${i + 1}@example-institute.test`,
  mobile: `07712345${String(i + 1).padStart(2, "0")}`,
  // 2026-11-05T03:00Z = 08:30 in Asia/Colombo.
  lastLoginAt: i === 0 ? "2026-11-05T03:00:00.000Z" : null,
  status: "ACTIVE",
  hasPhoto: false,
}));

const PENDING: Row[] = [
  {
    id: "stu-pending",
    indexNumber: "0099",
    firstName: "Pat",
    lastName: "Pending",
    email: "pat@example-institute.test",
    mobile: "0700000000",
    lastLoginAt: null,
    status: "ACTIVE",
    hasPhoto: false,
  },
];

const COURSES = [
  { id: "course-bio", name: "2027 A/L Biology" },
  { id: "course-maths", name: "Combined Maths" },
  { id: "course-ict", name: "ICT Theory" },
];

function detailOf(row: Row, extra: Record<string, unknown> = {}) {
  return {
    id: row.id,
    indexNumber: row.indexNumber,
    email: row.email,
    firstName: row.firstName,
    lastName: row.lastName,
    fullName: `${row.firstName} ${row.lastName}`,
    whatsappNumber: "0771112222",
    dateOfBirth: "2008-04-01",
    mobile: row.mobile,
    addressLine: "12 Temple Road",
    addressLine2: null,
    city: "Galle",
    postalCode: "80000",
    guardianName: "Sunil Perera",
    guardianPhone: "0713334444",
    guardianNic: "197512345678",
    fixedPhone: "0912223333",
    school: "Southlands College",
    alYear: 2027,
    stream: "Biology",
    status: "ACTIVE",
    pending: false,
    registeredAt: "2026-01-10T04:30:00.000Z",
    lastLoginAt: row.lastLoginAt,
    photoUrl: null,
    enrolledCourseIds: ["course-bio"],
    ...extra,
  };
}

interface Mocks {
  directoryRequests: URL[];
  requests: Request[];
  detailDelays: Map<string, number>;
  failDirectory: boolean;
}

async function fulfill(route: Route, status: number, body: unknown) {
  await route.fulfill({ status, contentType: "application/json", body: JSON.stringify(body) });
}

function pathOf(url: string): string {
  return new URL(url).pathname;
}

async function setup(page: Page, options: { role?: string; marker?: boolean } = {}): Promise<Mocks> {
  const context = page.context();
  const role = options.role ?? "TENANT_ADMIN";
  const mocks: Mocks = { directoryRequests: [], requests: [], detailDelays: new Map(), failDirectory: false };
  context.on("request", (request) => {
    if (request.url().includes("/api/v1/")) mocks.requests.push(request);
  });
  // Pin "now" so date-of-birth validation and the tenant clock are stable.
  await page.clock.install({ time: new Date("2026-11-07T04:30:00.000Z") });
  if (options.marker) {
    await context.addInitScript(() => window.sessionStorage.setItem("lms.impersonation", "1"));
  }

  await context.route("**/api/v1/**", (route) => fulfill(route, 404, apiError("NOT_FOUND", "Not mocked")));
  await context.route("**/v1/auth/refresh", (route) =>
    fulfill(route, 200, apiSuccess(refreshResponseBody(fakeJwt({ role, sub: "admin-1" }))))
  );
  await context.route("**/v1/tenant-config/GENERAL", (route) =>
    fulfill(route, 200, apiSuccess([{ key: "default_timezone", value: "Asia/Colombo", defaultValue: "Asia/Colombo" }]))
  );
  await context.route(
    (url) => url.pathname.endsWith("/v1/courses"),
    (route) => fulfill(route, 200, apiPageSuccess(COURSES))
  );
  await context.route(
    (url) => url.pathname.endsWith("/v1/students/directory"),
    async (route) => {
      const url = new URL(route.request().url());
      mocks.directoryRequests.push(url);
      if (mocks.failDirectory) return fulfill(route, 500, apiError("INTERNAL_ERROR", "Directory is down."));
      const tab = url.searchParams.get("tab") ?? "ACTIVE";
      const q = (url.searchParams.get("q") ?? "").toLowerCase();
      const page = Number(url.searchParams.get("page") ?? 0);
      const size = Number(url.searchParams.get("size") ?? 20);
      const source = tab === "PENDING" ? PENDING : ACTIVE;
      const filtered = q
        ? source.filter((row) =>
            [row.indexNumber, row.firstName, row.lastName, row.email, row.mobile].join(" ").toLowerCase().includes(q)
          )
        : source;
      const content = filtered.slice(page * size, page * size + size);
      return fulfill(route, 200, apiSuccess({
        content,
        page,
        size,
        totalElements: filtered.length,
        totalPages: Math.ceil(filtered.length / size),
      }));
    }
  );
  await context.route(
    (url) => /\/v1\/students\/[^/]+\/detail$/.test(url.pathname),
    async (route) => {
      const id = pathOf(route.request().url()).split("/").at(-2)!;
      const row = [...ACTIVE, ...PENDING].find((candidate) => candidate.id === id);
      if (!row) return fulfill(route, 404, apiError("NOT_FOUND", "Student not found."));
      const delay = mocks.detailDelays.get(id) ?? 0;
      if (delay) await new Promise((resolve) => setTimeout(resolve, delay));
      if (route.request().method() === "PUT") {
        const body = route.request().postDataJSON();
        return fulfill(route, 200, apiSuccess(detailOf(row, { ...body, fullName: `${body.firstName} ${body.lastName}`, enrolledCourseIds: body.courseIds })));
      }
      return fulfill(route, 200, apiSuccess(detailOf(row)));
    }
  );
  await context.route(
    (url) => /\/v1\/students\/[^/]+\/devices$/.test(url.pathname),
    (route) =>
      fulfill(route, 200, apiSuccess({
        devices: [
          {
            id: "dev-1",
            label: "Chrome on Windows",
            status: "ACTIVE",
            firstSeenAt: "2026-10-01T00:00:00Z",
            lastSeenAt: "2026-11-05T03:00:00Z",
            lastIp: "10.0.0.1",
            revokedAt: null,
            revocationSource: null,
            revokeReason: null,
            slotReusableAt: null,
            current: false,
          },
        ],
        limit: 2,
        limitSource: "PLAN",
        occupiedSlots: 1,
        nextSlotFreeAt: null,
        studentOverride: null,
        studentOverrideReason: null,
        selfServiceEnabled: false,
        resetCooldownHours: 24,
      }))
  );
  return mocks;
}

async function gotoDirectory(page: Page) {
  await page.goto("/tenant-admin/students");
  await expect(page.getByRole("heading", { name: "Students", exact: true })).toBeVisible({ timeout: NAV_TIMEOUT });
  await expect(page.getByRole("table", { name: "Active students" })).toBeVisible({ timeout: NAV_TIMEOUT });
}

function rowActions(page: Page, id: string) {
  // The DataTable renders a desktop table and (hidden) mobile cards; scope to the visible one.
  return page.getByRole("table").getByTestId(`student-row-actions-${id}`);
}

test.describe("Students directory", () => {
  test("tabs, columns, black list tooltip, accessible row actions and menu", async ({ page }) => {
    const mocks = await setup(page);
    await gotoDirectory(page);

    const table = page.getByRole("table", { name: "Active students" });
    for (const header of ["Index Number", "First Name", "Last Name", "Email", "Mobile", "Last Login", "Actions"]) {
      await expect(table.getByRole("columnheader", { name: header })).toBeVisible();
    }
    const first = table.getByRole("row").nth(1);
    await expect(first).toContainText("0001");
    await expect(first).toContainText("0771234501");
    // Tenant timezone, 24 h.
    await expect(first).toContainText("2026-11-05 08:30");
    await expect(table.getByRole("row").nth(2)).toContainText("Never");
    expect(mocks.directoryRequests[0].searchParams.get("tab")).toBe("ACTIVE");
    expect(mocks.directoryRequests[0].searchParams.get("field")).toBe("ALL");

    const blackList = page.getByTestId("students-tab-blacklist");
    await expect(blackList).toHaveAttribute("aria-disabled", "true");
    await blackList.hover();
    await expect(page.getByTestId("tooltip")).toHaveText("Not available");

    // Accessible labels on icon-only row actions.
    const actions = rowActions(page, "stu-01");
    await expect(actions.getByRole("button", { name: "Login As Student: Ada Lovelace" })).toBeVisible();
    await expect(actions.getByRole("button", { name: "View Student Details: Ada Lovelace" })).toBeVisible();
    await expect(actions.getByRole("button", { name: "Edit Student: Ada Lovelace" })).toBeVisible();
    await actions.getByRole("button", { name: "More actions: Ada Lovelace" }).click();
    const menu = page.getByRole("menu");
    await expect(menu.getByRole("menuitem")).toHaveText([
      "Comprehensive Report",
      "Send Message",
      "Reset Password",
      "Delete Student",
    ]);
    await expect(menu.getByRole("menuitem", { name: "Comprehensive Report" })).toHaveAttribute("href", "/tenant-admin/students/stu-01");
    await expect(menu.getByRole("menuitem", { name: "Send Message" })).toHaveAttribute(
      "href",
      "/tenant-admin/communications/new?studentId=stu-01"
    );
    await menu.getByRole("menuitem", { name: "Reset Password" }).click();
    await expect(page.getByRole("alertdialog")).toContainText("Reset Ada Lovelace's password?");
    await page.keyboard.press("Escape");

    // Pending tab: server tab param + Approve action.
    await page.getByRole("tab", { name: "Pending" }).click();
    await expect(page.getByRole("table", { name: "Pending students" })).toBeVisible();
    expect(mocks.directoryRequests.at(-1)!.searchParams.get("tab")).toBe("PENDING");
    let approved = false;
    await page.context().route("**/v1/students/stu-pending/approve", (route) => {
      approved = true;
      return fulfill(route, 200, apiSuccess(detailOf(PENDING[0])));
    });
    await rowActions(page, "stu-pending").getByRole("button", { name: "Approve: Pat Pending" }).click();
    await expect(page.getByText("Pat Pending was approved.")).toBeVisible();
    expect(approved).toBe(true);
    await expect(rowActions(page, "stu-pending").getByRole("button", { name: /Login As Student/ })).toHaveCount(0);
  });

  test("search field + 300 ms debounce resets the page; CSV export sends the current filters", async ({ page }) => {
    const mocks = await setup(page);
    await gotoDirectory(page);

    await page.getByRole("button", { name: "Next", exact: true }).click();
    await expect(page.getByTestId("directory-summary")).toContainText("Page 2 of 2");
    expect(mocks.directoryRequests.at(-1)!.searchParams.get("page")).toBe("1");

    await page.getByLabel("Search Field").selectOption("FIRST_NAME");
    const before = mocks.directoryRequests.length;
    await page.getByLabel("Search", { exact: true }).pressSequentially("grace", { delay: 40 });
    await expect(page.getByTestId("directory-summary")).toContainText("3 students", { timeout: 5000 });
    const searchRequests = mocks.directoryRequests.slice(before).filter((url) => url.searchParams.has("q"));
    // Debounced: one request for the whole word, never one per keystroke.
    expect(searchRequests.map((url) => url.searchParams.get("q"))).toEqual(["grace"]);
    expect(searchRequests[0].searchParams.get("field")).toBe("FIRST_NAME");
    expect(searchRequests[0].searchParams.get("page")).toBe("0");

    let csvUrl: URL | null = null;
    await page.context().route("**/v1/students/directory/export.csv*", (route) => {
      csvUrl = new URL(route.request().url());
      return route.fulfill({ status: 200, contentType: "text/csv", body: "Index Number,First Name\n0002,Grace\n" });
    });
    const download = page.waitForEvent("download");
    await page.getByRole("button", { name: "Export as CSV" }).click();
    expect((await download).suggestedFilename()).toBe("students-active.csv");
    expect(csvUrl!.searchParams.get("tab")).toBe("ACTIVE");
    expect(csvUrl!.searchParams.get("field")).toBe("FIRST_NAME");
    expect(csvUrl!.searchParams.get("q")).toBe("grace");
  });

  test("no-match state offers clear search; load failure shows an error, not an empty list", async ({ page }) => {
    const mocks = await setup(page);
    await gotoDirectory(page);
    await page.getByLabel("Search", { exact: true }).fill("zzz");
    await expect(page.getByText("No students match your search")).toBeVisible();
    await page.getByRole("button", { name: "Clear search" }).click();
    await expect(page.getByRole("table", { name: "Active students" })).toBeVisible();

    mocks.failDirectory = true;
    await page.getByRole("tab", { name: "Pending" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Directory is down." })).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText("No pending registrations")).toHaveCount(0);
  });

  test("Student Support sees no Login As Student and no Delete", async ({ page }) => {
    await setup(page, { role: "STUDENT_SUPPORT" });
    await gotoDirectory(page);
    const actions = rowActions(page, "stu-01");
    await expect(actions.getByRole("button", { name: /View Student Details/ })).toBeVisible();
    await expect(actions.getByRole("button", { name: /Login As Student/ })).toHaveCount(0);
    await actions.getByRole("button", { name: /More actions/ }).click();
    await expect(page.getByRole("menuitem", { name: "Delete Student" })).toHaveCount(0);
    await expect(page.getByRole("menuitem", { name: "Reset Password" })).toBeVisible();
  });
});

test.describe("Create / edit student", () => {
  test("Klass field order, validation, blank password → one-time temporary password", async ({ page }) => {
    await setup(page);
    let payload: Record<string, unknown> | null = null;
    await page.context().route("**/v1/students/v2", (route) => {
      payload = route.request().postDataJSON();
      const created = detailOf({ ...ACTIVE[0], id: "stu-new", indexNumber: "0026", firstName: "Nimal", lastName: "Silva" });
      return fulfill(route, 201, apiSuccess({ student: created, temporaryPassword: "Temp-Pass-123" }));
    });
    await gotoDirectory(page);
    await page.getByRole("button", { name: "Add Student" }).click();
    const sheet = page.getByTestId("student-form-sheet");
    await expect(sheet.getByRole("heading", { name: "Add New Student" })).toBeVisible();

    const order = await sheet.locator("[data-field]").evaluateAll((nodes) => nodes.map((node) => node.getAttribute("data-field")));
    expect(order).toEqual([
      "indexNumber",
      "email",
      "password",
      "firstName",
      "lastName",
      "whatsappNumber",
      "dateOfBirth",
      "mobile",
      "addressLine",
      "addressLine2",
      "city",
      "postalCode",
      "guardianName",
      "guardianPhone",
      "guardianNic",
      "courseIds",
      "fixedPhone",
      "school",
      "alYear",
      "stream",
    ]);
    await expect(sheet.getByLabel("Index Number")).toHaveValue("Assigned automatically");

    await sheet.getByRole("button", { name: "Create Student" }).click();
    await expect(sheet.getByText("First name is required.")).toBeVisible();
    await expect(sheet.getByText("Last name is required.")).toBeVisible();
    await expect(sheet.getByText("Email is required.")).toBeVisible();
    await expect(sheet.getByText("Mobile number is required.")).toBeVisible();
    await expect(sheet.getByText("Select at least one course.", { exact: true })).toBeVisible();

    await sheet.getByLabel("Date of Birth").fill("2030-01-01");
    await sheet.getByLabel("Email").fill("nimal@example-institute.test");
    await sheet.getByLabel("First Name").fill("Nimal");
    await sheet.getByLabel("Last Name").fill("Silva");
    await sheet.getByLabel("Mobile Number").fill("0771234599");
    await sheet.getByRole("checkbox", { name: "Combined Maths" }).check();
    await sheet.getByRole("button", { name: "Create Student" }).click();
    await expect(sheet.getByText("Date of birth can't be in the future.")).toBeVisible();
    await sheet.getByLabel("Date of Birth").fill("");
    await sheet.getByRole("button", { name: "Show password" }).click();
    await expect(sheet.getByLabel("Password", { exact: true })).toHaveAttribute("type", "text");
    await sheet.getByRole("button", { name: "Create Student" }).click();

    await expect(sheet.getByTestId("temporary-password")).toHaveText("Temp-Pass-123");
    expect(payload).toMatchObject({
      email: "nimal@example-institute.test",
      password: null,
      firstName: "Nimal",
      lastName: "Silva",
      mobile: "0771234599",
      dateOfBirth: null,
      courseIds: ["course-maths"],
    });
    expect(payload).not.toHaveProperty("tenantId");
    expect(payload).not.toHaveProperty("indexNumber");
    await sheet.getByRole("button", { name: "Done" }).click();
    await expect(sheet).toBeHidden();
  });

  test("server field errors map onto fields", async ({ page }) => {
    await setup(page);
    await page.context().route("**/v1/students/v2", (route) =>
      fulfill(route, 400, apiError("VALIDATION_ERROR", "Invalid", [{ field: "mobile", message: "Mobile number is already used." }]))
    );
    await gotoDirectory(page);
    await page.getByRole("button", { name: "Add Student" }).click();
    const sheet = page.getByTestId("student-form-sheet");
    await sheet.getByLabel("Email").fill("x@example-institute.test");
    await sheet.getByLabel("First Name").fill("X");
    await sheet.getByLabel("Last Name").fill("Y");
    await sheet.getByLabel("Mobile Number").fill("0771234501");
    await sheet.getByRole("checkbox", { name: "ICT Theory" }).check();
    await sheet.getByRole("button", { name: "Create Student" }).click();
    await expect(sheet.locator('[data-field="mobile"]').getByRole("alert")).toHaveText("Mobile number is already used.");
  });

  test("edit: read-only index, no password, enrolled courses locked (add-only)", async ({ page }) => {
    await setup(page);
    await gotoDirectory(page);
    let putBody: Record<string, unknown> | null = null;
    page.context().on("request", (request) => {
      if (request.method() === "PUT" && request.url().endsWith("/v1/students/stu-01/detail")) putBody = request.postDataJSON();
    });
    await rowActions(page, "stu-01").getByRole("button", { name: "Edit Student: Ada Lovelace" }).click();
    const sheet = page.getByTestId("student-form-sheet");
    await expect(sheet.getByRole("heading", { name: "Edit Student" })).toBeVisible();
    await expect(sheet.getByLabel("Index Number")).toHaveValue("0001");
    await expect(sheet.getByLabel("Password", { exact: true })).toHaveCount(0);
    await expect(sheet.getByLabel("First Name")).toHaveValue("Ada");
    // Email changes are refused server-side on edit, so the field is read-only.
    await expect(sheet.getByLabel("Email")).toHaveAttribute("readonly", "");
    await expect(sheet.getByLabel("Mobile Number")).toHaveValue("0771234501");
    const bio = sheet.getByRole("checkbox", { name: "2027 A/L Biology" });
    await expect(bio).toBeChecked();
    await expect(bio).toBeDisabled();
    await sheet.getByRole("checkbox", { name: "ICT Theory" }).check();
    await sheet.getByLabel("School").fill("Richmond College");
    await sheet.getByRole("button", { name: "Save Changes" }).click();
    await expect(sheet).toBeHidden();
    await expect(page.getByText("Ada Lovelace was updated.")).toBeVisible();
    expect(putBody).toMatchObject({ school: "Richmond College", courseIds: ["course-bio", "course-ict"] });
    expect(putBody).not.toHaveProperty("password");
  });
});

test.describe("Details drawer", () => {
  test("rapid switching never shows the previous student's data", async ({ page }) => {
    const mocks = await setup(page);
    mocks.detailDelays.set("stu-01", 2500);
    await gotoDirectory(page);

    await rowActions(page, "stu-01").getByRole("button", { name: /View Student Details/ }).click();
    const drawer = page.getByTestId("student-details-drawer");
    await expect(drawer.getByTestId("student-drawer-loading")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(drawer).toBeHidden();
    await rowActions(page, "stu-02").getByRole("button", { name: /View Student Details/ }).click();
    await expect(drawer.getByTestId("drawer-student-name")).toHaveText("Grace Hopper");
    // Let the slow Ada response land; Grace must still be shown.
    await page.waitForTimeout(3000);
    await expect(drawer.getByTestId("student-drawer-content")).toHaveAttribute("data-student-id", "stu-02");
    await expect(drawer).not.toContainText("Ada Lovelace");
    await expect(drawer.getByTestId("drawer-student-index")).toHaveText("0002");
    await expect(drawer.getByTestId("drawer-device-count")).toHaveText("1");
    await expect(drawer.getByTestId("drawer-courses")).toContainText("2027 A/L Biology");
    await expect(drawer.getByRole("link", { name: "See Full Profile" })).toHaveAttribute("href", "/tenant-admin/students/stu-02");

    let remark: unknown = null;
    await page.context().route("**/v1/students/stu-02/remarks", (route) => {
      remark = route.request().postDataJSON();
      return fulfill(route, 201, apiSuccess({ id: "r1", body: "Called parent", authorName: "Admin", createdAt: "2026-11-07T04:00:00Z" }));
    });
    await drawer.getByRole("button", { name: "Add Remark" }).click();
    const dialog = page.getByTestId("add-remark-dialog");
    await dialog.getByLabel("Remark").fill("Called parent");
    await dialog.getByRole("button", { name: "Add Remark" }).click();
    await expect(dialog).toBeHidden();
    expect(remark).toEqual({ body: "Called parent" });
  });
});

async function setupProfile(page: Page, options: { paymentsFail?: boolean } = {}) {
  const mocks = await setup(page);
  const remarkUrls: URL[] = [];
  const context = page.context();
  await context.route(
    (url) => url.pathname.endsWith("/v1/students/stu-01/remarks"),
    (route) => {
      remarkUrls.push(new URL(route.request().url()));
      return fulfill(route, 200, apiPageSuccess([
        { id: "r1", body: "<b>Parent</b> called", authorName: "Admin One", createdAt: "2026-11-01T04:00:00Z" },
      ]));
    }
  );
  await context.route(
    (url) => url.pathname.endsWith("/v1/exams/students/stu-01/attempts"),
    (route) =>
      fulfill(route, 200, apiPageSuccess([
        { id: "a1", examId: "exam-aaaa1111", studentId: "stu-01", startedAt: "2026-03-30T03:00:00Z", submittedAt: "2026-03-30T04:00:00Z", status: "GRADED", examTitle: "Demo MCQ", passed: false },
        { id: "a2", examId: "exam-bbbb2222", studentId: "stu-01", startedAt: "2026-10-30T03:00:00Z", submittedAt: "2026-10-30T04:00:00Z", status: "GRADED", examTitle: "Unit Test 2", passed: true },
      ]))
  );
  await context.route(
    (url) => url.pathname.endsWith("/v1/students/stu-01/ledger"),
    (route) =>
      options.paymentsFail
        ? fulfill(route, 500, apiError("INTERNAL_ERROR", "Ledger unavailable."))
        : fulfill(route, 200, apiSuccess([
            { id: "l1", orderId: "order-1111", paymentId: "p1", entryType: "PAYMENT_CONFIRMED", amount: 0, reversesEntryId: null, createdAt: "2026-10-02T04:00:00Z", courseId: "course-bio", courseTitle: "2027 A/L Biology", billingPeriodId: null, operationalState: "PAID", method: "FREE", reference: null },
          ]))
  );
  await context.route(
    (url) => url.pathname.endsWith("/v1/students/stu-01/qr.png"),
    (route) => route.fulfill({ status: 200, contentType: "image/png", body: Buffer.from(TINY_PNG, "base64") })
  );
  await context.route(
    (url) => url.pathname.endsWith("/v1/students/stu-01/enrollments"),
    (route) => fulfill(route, 200, apiSuccess([]))
  );
  return { mocks, remarkUrls };
}

// 1×1 transparent PNG.
const TINY_PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";

test.describe("Full profile", () => {
  test("sections, identity sidebar, QR, and date filter params", async ({ page }) => {
    const { remarkUrls } = await setupProfile(page);
    await page.goto("/tenant-admin/students/stu-01");
    await expect(page.getByRole("heading", { name: "Student Profile" })).toBeVisible({ timeout: NAV_TIMEOUT });
    const crumbs = page.getByRole("navigation", { name: "Breadcrumb" });
    await expect(crumbs.getByRole("link", { name: "Students" })).toHaveAttribute("href", "/tenant-admin/students");
    await expect(crumbs.getByText("Student Profile")).toHaveAttribute("aria-current", "page");

    const identity = page.getByTestId("student-identity");
    await expect(identity.getByTestId("profile-student-index")).toHaveText("0001");
    await expect(identity.getByTestId("profile-guardian")).toContainText("Sunil Perera · 0713334444 · 197512345678");
    await expect(identity).toContainText("12 Temple Road, Galle, 80000");
    await expect(identity).toContainText("2026-01-10 10:00");
    await expect(page.getByTestId("student-qr-image")).toBeVisible();

    const remarks = page.getByTestId("profile-remarks");
    // Rendered as text, never HTML.
    await expect(remarks).toContainText("<b>Parent</b> called");
    await expect(remarks.locator("b")).toHaveCount(0);

    const exams = page.getByTestId("profile-exams");
    await expect(exams).toContainText("Demo MCQ");
    await expect(exams.getByText("FAIL", { exact: true })).toBeVisible();
    await expect(exams.getByText("PASS", { exact: true })).toBeVisible();

    await expect(page.getByTestId("profile-courses")).toContainText("2027 A/L Biology");
    const payments = page.getByTestId("profile-payments");
    await expect(payments).toContainText("Free enrollment");
    await expect(payments).not.toContainText(/cash/i);

    await expect(page.getByText("Enrolled courses always show current enrolments.")).toBeVisible();
    await page.getByLabel("From", { exact: true }).fill("2026-10-01");
    await page.getByLabel("To", { exact: true }).fill("2026-10-31");
    await expect
      .poll(() => [remarkUrls.at(-1)?.searchParams.get("from"), remarkUrls.at(-1)?.searchParams.get("to")])
      // Inclusive tenant-zone days (Asia/Colombo, UTC+05:30) sent as ISO instants.
      .toEqual(["2026-09-30T18:30:00.000Z", "2026-10-31T18:29:59.999Z"]);
    await expect(exams).not.toContainText("Demo MCQ");
    await expect(exams).toContainText("Unit Test 2");
    // Enrolled courses are not date-filtered.
    await expect(page.getByTestId("profile-courses")).toContainText("2027 A/L Biology");

    await page.getByTestId("profile-courses").getByRole("button", { name: "Show Attendance" }).click();
    await expect(page.getByTestId("course-attendance-dialog")).toBeVisible();
    await page.keyboard.press("Escape");

    // Existing admin tools stay reachable.
    const manage = page.getByTestId("profile-manage");
    await expect(manage.getByRole("tab", { name: "Devices" })).toBeVisible();
    await expect(manage.getByRole("tab", { name: "Enrollments" })).toBeVisible();
  });

  test("a failed section shows an error state, never an empty state", async ({ page }) => {
    await setupProfile(page, { paymentsFail: true });
    await page.goto("/tenant-admin/students/stu-01");
    const payments = page.getByTestId("profile-payments");
    await expect(payments.getByRole("alert")).toContainText("Ledger unavailable.", { timeout: NAV_TIMEOUT });
    await expect(payments).not.toContainText("No ledger-confirmed payments");
  });
});

async function pngBuffer(page: Page): Promise<Buffer> {
  const dataUrl = await page.evaluate(() => {
    const canvas = document.createElement("canvas");
    canvas.width = 160;
    canvas.height = 120;
    const ctx = canvas.getContext("2d")!;
    ctx.fillStyle = "#3366cc";
    ctx.fillRect(0, 0, 160, 120);
    return canvas.toDataURL("image/png");
  });
  return Buffer.from(dataUrl.split(",")[1], "base64");
}

test.describe("Profile photo editor", () => {
  test("crop, upload as JPEG multipart, then remove", async ({ page }) => {
    const { mocks } = await setupProfile(page);
    let hasPhoto = false;
    let uploadType = "";
    let deleted = false;
    await page.context().route(
      (url) => url.pathname.endsWith("/v1/students/stu-01/photo"),
      async (route) => {
        const method = route.request().method();
        if (method === "POST") {
          uploadType = route.request().headers()["content-type"] ?? "";
          const body = route.request().postDataBuffer()?.toString("latin1") ?? "";
          expect(body).toContain("Content-Type: image/jpeg");
          hasPhoto = true;
          return fulfill(route, 200, apiSuccess(detailOf(ACTIVE[0])));
        }
        if (method === "DELETE") {
          deleted = true;
          hasPhoto = false;
          return route.fulfill({ status: 204 });
        }
        return hasPhoto
          ? fulfill(route, 200, apiSuccess({ url: `data:image/png;base64,${TINY_PNG}`, expiresAt: "2026-11-07T05:00:00Z" }))
          : fulfill(route, 404, apiError("NOT_FOUND", "No photo"));
      }
    );
    void mocks;
    await page.goto("/tenant-admin/students/stu-01");
    const identity = page.getByTestId("student-identity");
    await identity.getByRole("button", { name: "Change photo" }).click();
    const editor = identity.getByTestId("profile-photo-editor");
    await expect(editor).toContainText("JPG or PNG, max 2 MB, square ~400×400 px recommended.");
    await editor.getByLabel("Upload photo").setInputFiles({ name: "me.png", mimeType: "image/png", buffer: await pngBuffer(page) });
    await expect(editor.getByTestId("photo-cropper")).toBeVisible();
    await editor.getByLabel("Zoom").fill("2");
    await editor.getByRole("button", { name: "Save photo" }).click();
    await expect(editor.getByRole("status")).toContainText("Profile photo updated.");
    expect(uploadType).toContain("multipart/form-data");
    await expect(editor.getByRole("img", { name: "Ada Lovelace profile photo" })).toBeVisible();

    await editor.getByRole("button", { name: "Remove photo" }).click();
    await expect(editor.getByRole("status")).toContainText("Profile photo removed.");
    expect(deleted).toBe(true);
    await expect(editor.getByRole("img", { name: "Ada Lovelace profile photo" })).toHaveCount(0);
  });

  test("rejects an unsupported or oversized file before upload", async ({ page }) => {
    await setupProfile(page);
    await page.goto("/tenant-admin/students/stu-01");
    const identity = page.getByTestId("student-identity");
    await identity.getByRole("button", { name: "Change photo" }).click();
    const editor = identity.getByTestId("profile-photo-editor");
    await editor.getByLabel("Upload photo").setInputFiles({ name: "x.gif", mimeType: "image/gif", buffer: Buffer.from("GIF89a") });
    await expect(editor.getByRole("alert")).toHaveText("Choose a JPG or PNG image.");
    await editor
      .getByLabel("Upload photo")
      .setInputFiles({ name: "big.jpg", mimeType: "image/jpeg", buffer: Buffer.alloc(2 * 1024 * 1024 + 1) });
    await expect(editor.getByRole("alert")).toHaveText("The image is larger than 2 MB. Choose a smaller image.");
  });
});

test.describe("Bulk Print QRs", () => {
  test("selection survives search and paging; select-all covers every match; export sends exactly the ids", async ({ page }) => {
    const mocks = await setup(page);
    let exported: string[] | null = null;
    await page.context().route("**/v1/students/qr-export", (route) => {
      exported = route.request().postDataJSON().studentIds;
      return route.fulfill({ status: 200, contentType: "application/pdf", body: "%PDF-1.4\n%%EOF" });
    });
    await gotoDirectory(page);
    await page.getByRole("button", { name: "Export QRs" }).click();
    const dialog = page.getByTestId("bulk-qr-dialog");
    await expect(dialog).toContainText("Following selected student QRs will be exported");
    await expect(dialog.getByTestId("bulk-qr-row")).toHaveCount(10);
    await expect(dialog.getByRole("columnheader", { name: "NIC" })).toHaveCount(0);

    await dialog.getByRole("checkbox", { name: "Select Ada Lovelace", exact: true }).check();
    await dialog.getByRole("button", { name: "Next", exact: true }).click();
    await expect(dialog.getByText("Page 2 of 3")).toBeVisible();
    await dialog.getByRole("checkbox", { name: "Select Ada Lovelace 1" }).check();
    await expect(dialog.getByTestId("bulk-qr-selected-count")).toHaveText("2 selected");

    await dialog.getByLabel("Search Students").fill("hopper");
    await expect(dialog.getByTestId("bulk-qr-row")).toHaveCount(3);
    const before = mocks.directoryRequests.length;
    // Controlled by the async "fetch all matching ids" call, so click (not check).
    await dialog.getByRole("checkbox", { name: "Select all students matching “hopper”" }).click();
    await expect(dialog.getByTestId("bulk-qr-selected-count")).toHaveText("5 selected");
    const selectAllRequest = mocks.directoryRequests.slice(before).find((url) => url.searchParams.get("size") === "100");
    expect(selectAllRequest?.searchParams.get("q")).toBe("hopper");

    // Back to the full list: earlier picks are still selected.
    await dialog.getByLabel("Search Students").fill("");
    await expect(dialog.getByRole("checkbox", { name: "Select Ada Lovelace", exact: true })).toBeChecked();

    const download = page.waitForEvent("download");
    await dialog.getByRole("button", { name: "Export" }).click();
    expect((await download).suggestedFilename()).toBe("student-qr-labels.pdf");
    expect(new Set(exported!)).toEqual(new Set(["stu-01", "stu-11", "stu-02", "stu-12", "stu-22"]));
    expect(exported!.length).toBe(5);
    await expect(dialog).toBeHidden();
  });
});

const STUDENT_TOKEN = () => fakeJwt({ role: "STUDENT", sub: "stu-01", imp: true, act: "admin-1" });

async function mockImpersonation(context: BrowserContext, options: { tokenFails?: boolean } = {}) {
  const calls = { token: 0, end: 0, refreshDuringImpersonation: 0 };
  await context.route("**/v1/auth/impersonation/token", (route) => {
    calls.token += 1;
    if (options.tokenFails) return fulfill(route, 401, apiError("IMPERSONATION_INVALID", "Support session ended."));
    return fulfill(route, 200, apiSuccess({
      accessToken: STUDENT_TOKEN(),
      expiresAt: "2026-11-07T05:00:00.000Z",
      sessionId: "imp-1",
      student: { id: "stu-01", indexNumber: "0001", fullName: "Ada Lovelace" },
      actorName: "Admin One",
    }));
  });
  await context.route("**/v1/auth/impersonation/end", (route) => {
    calls.end += 1;
    return route.fulfill({ status: 204 });
  });
  return calls;
}

test.describe("Log as Student (ADR-033)", () => {
  test("start dialog → /impersonate → banner → exit clears the session and returns to admin", async ({ page }) => {
    const mocks = await setup(page);
    const context = page.context();
    const calls = await mockImpersonation(context);
    let startBody: unknown = null;
    await context.route("**/v1/students/stu-01/impersonation", (route) => {
      startBody = route.request().postDataJSON();
      return fulfill(route, 201, apiSuccess({
        sessionId: "imp-1",
        expiresAt: "2026-11-07T05:00:00.000Z",
        student: { id: "stu-01", indexNumber: "0001", fullName: "Ada Lovelace" },
      }));
    });
    await gotoDirectory(page);

    await rowActions(page, "stu-01").getByRole("button", { name: "Login As Student: Ada Lovelace" }).click();
    const dialog = page.getByTestId("log-as-student-dialog");
    await expect(dialog.getByTestId("log-as-student-target")).toContainText("0001 · Ada Lovelace");
    await expect(dialog).toContainText("read-only support session");
    await expect(dialog).toContainText("30 minutes");
    await dialog.getByLabel("Reason").fill("abc");
    await dialog.getByRole("button", { name: "Start support session" }).click();
    await expect(dialog.getByText("Give a reason of at least 5 characters.")).toBeVisible();
    await dialog.getByLabel("Reason").fill("Student reports a missing lesson");

    const popupPromise = context.waitForEvent("page");
    await dialog.getByRole("button", { name: "Start support session" }).click();
    const popup = await popupPromise;
    expect(startBody).toEqual({ reason: "Student reports a missing lesson" });

    const refreshesBefore = mocks.requests.filter((r) => r.url().endsWith("/v1/auth/refresh") && r.frame().page() === popup).length;
    await expect(popup).toHaveURL(/\/student\/dashboard$/, { timeout: NAV_TIMEOUT });
    const banner = popup.getByTestId("impersonation-banner");
    await expect(banner).toContainText("Viewing as Ada Lovelace (0001) — read-only support session · ends");
    expect(await popup.evaluate(() => window.sessionStorage.getItem("lms.impersonation"))).toBe("1");
    expect(calls.token).toBeGreaterThanOrEqual(1);
    const popupRefreshes = mocks.requests.filter((r) => r.url().endsWith("/v1/auth/refresh") && r.frame().page() === popup).length;
    expect(popupRefreshes - refreshesBefore).toBe(0);
    // The admin tab is untouched.
    await expect(page.getByRole("heading", { name: "Students", exact: true })).toBeVisible();
    expect(await page.evaluate(() => window.sessionStorage.getItem("lms.impersonation"))).toBeNull();

    await banner.getByRole("button", { name: "Exit to admin" }).click();
    await expect(popup).toHaveURL(/\/tenant-admin\/students$/, { timeout: NAV_TIMEOUT });
    expect(calls.end).toBe(1);
    expect(await popup.evaluate(() => window.sessionStorage.getItem("lms.impersonation"))).toBeNull();
    await expect(popup.getByRole("heading", { name: "Students", exact: true })).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(popup.getByTestId("impersonation-banner")).toHaveCount(0);
  });

  test("a refused start (STUDENT_NOT_ACTIVE) is explained and opens no tab", async ({ page }) => {
    await setup(page);
    await page.context().route("**/v1/students/stu-01/impersonation", (route) =>
      fulfill(route, 409, apiError("STUDENT_NOT_ACTIVE", "Student is not active."))
    );
    await gotoDirectory(page);
    await rowActions(page, "stu-01").getByRole("button", { name: "Login As Student: Ada Lovelace" }).click();
    const dialog = page.getByTestId("log-as-student-dialog");
    await dialog.getByLabel("Reason").fill("Checking a missing lesson");
    let opened = false;
    page.context().on("page", () => (opened = true));
    await dialog.getByRole("button", { name: "Start support session" }).click();
    await expect(dialog.getByText("isn't active (pending approval or suspended)").first()).toBeVisible();
    expect(opened).toBe(false);
  });

  test("reload in a support tab re-acquires via the impersonation endpoint, never /auth/refresh", async ({ page }) => {
    const mocks = await setup(page, { marker: true });
    const calls = await mockImpersonation(page.context());
    await page.goto("/student/dashboard");
    await expect(page.getByTestId("impersonation-banner")).toContainText("Viewing as Ada Lovelace (0001)", { timeout: NAV_TIMEOUT });
    expect(calls.token).toBeGreaterThanOrEqual(1);
    expect(mocks.requests.filter((r) => r.url().endsWith("/v1/auth/refresh"))).toHaveLength(0);
  });

  test("an ended support session shows the ended page and leads back to admin", async ({ page }) => {
    const mocks = await setup(page, { marker: true });
    await mockImpersonation(page.context(), { tokenFails: true });
    await page.goto("/student/dashboard");
    const ended = page.getByTestId("support-session-ended");
    await expect(ended.getByRole("heading", { name: "Support session ended" })).toBeVisible({ timeout: NAV_TIMEOUT });
    expect(mocks.requests.filter((r) => r.url().endsWith("/v1/auth/refresh"))).toHaveLength(0);
    await expect(page).not.toHaveURL(/\/login/);

    // Clearing the marker (it is set by an init script on every load in this test) is checked in-page.
    await page.evaluate(() => window.sessionStorage.removeItem("lms.impersonation"));
    await ended.getByRole("button", { name: "Back to students" }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/students$/, { timeout: NAV_TIMEOUT });
  });

  test("read-only refusal is explained in the banner", async ({ page }) => {
    await setup(page, { marker: true });
    await mockImpersonation(page.context());
    await page.goto("/student/profile");
    await expect(page.getByTestId("impersonation-banner")).toBeVisible({ timeout: NAV_TIMEOUT });
    await page.context().route(
      (url) => url.pathname.endsWith("/v1/students/me/photo"),
      (route) =>
        route.request().method() === "POST"
          ? fulfill(route, 403, apiError("IMPERSONATION_READ_ONLY", "Support sessions are read-only."))
          : fulfill(route, 404, apiError("NOT_FOUND", "No photo"))
    );
    await page.context().route(
      (url) => url.pathname.endsWith("/v1/students/me"),
      (route) => fulfill(route, 200, apiSuccess({ id: "stu-01", name: "Ada Lovelace", email: "student1@example-institute.test", roleCode: "STUDENT", status: "ACTIVE" }))
    );
    await page.reload();
    const editor = page.getByTestId("profile-photo-editor");
    await editor.getByLabel("Upload photo").setInputFiles({ name: "me.png", mimeType: "image/png", buffer: await pngBuffer(page) });
    await editor.getByRole("button", { name: "Save photo" }).click();
    await expect(page.getByTestId("impersonation-read-only-notice")).toContainText("read-only support session");
  });
});
