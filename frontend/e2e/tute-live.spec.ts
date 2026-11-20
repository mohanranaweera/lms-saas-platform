import { test, expect, request, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { ADMIN_EMAIL, TENANT_BASE, api, apiLogin, loginUi } from "./fixtures/klass/klass-api";

/**
 * TUTE-01 live UI evidence (A7 UI part + A9) against the LOCAL stack only (opt-in: `TUTE_LIVE=1`).
 * Run `e2e/tute-live-api.spec.ts` first: it creates the synthetic data and writes
 * docs/qa/tute-management-evidence/live-ids.json, which this spec reads.
 *
 * - A9: screenshots at 1440x900 and 390x844 of the course More menu (Manage Tutes), Tutes of
 *   [Course], Add Tute (empty, Paid + Price), Edit (Save Changes), tute detail, Tute Dispatch
 *   with the selection banner and the Staff permission Tute group, plus horizontal overflow.
 * - A7: grant Operator TUTE_DISPATCH_VIEW through Staff -> Permissions (save + reopen), Operator
 *   sees Tute Dispatch without Print/Confirm; grant PRINT_LABELS -> print works; revoke ->
 *   the next print on the same signed-in page is 403, and after reload the button is gone.
 *   Every Tute cell is restored to matrix-baseline.json in afterAll.
 * - PDFs: renders labels-small.pdf / labels-large.pdf (and the Klass reference) to PNG with
 *   e2e/tools/RenderPdf.java (PDFBox from the local Maven cache).
 */
test.skip(!process.env.TUTE_LIVE, "live backend check; set TUTE_LIVE=1");
test.describe.configure({ mode: "serial" });
test.setTimeout(180_000);

const EVIDENCE = path.resolve(__dirname, "../../docs/qa/tute-management-evidence");
const REFERENCES = path.resolve(__dirname, "../../docs/references/klass-tute-management");
const IDS_FILE = path.join(EVIDENCE, "live-ids.json");
const ids = fs.existsSync(IDS_FILE) ? JSON.parse(fs.readFileSync(IDS_FILE, "utf-8")) : null;
const VIEWPORTS = [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 },
] as const;
const report: Record<string, unknown> = {};

async function shot(page: Page, name: string, settleMs = 500) {
  await page.waitForTimeout(settleMs); // dialog/menu animations (evidence only)
  await page.screenshot({ path: path.join(EVIDENCE, `${name}.png`), fullPage: false });
}
async function overflow(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
}

type MatrixRow = { key: string; cells: Record<string, { state: string }> };
type MatrixData = { version: number; groups: { key: string; rows: MatrixRow[] }[] };

async function tuteMatrix() {
  const ctx = await request.newContext();
  const token = await apiLogin(ctx, ADMIN_EMAIL);
  const r = await api<MatrixData>(ctx, token, "GET", "/staff/permission-matrix");
  await ctx.dispose();
  const matrix = r.body.data;
  if (!matrix) throw new Error("permission matrix response had no data");
  const group = matrix.groups.find((g) => g.key === "TUTE");
  if (!group) throw new Error("Tute group missing from the permission matrix");
  return {
    version: matrix.version,
    tute: Object.fromEntries(group.rows.map((row) => [row.key, Object.fromEntries(Object.entries(row.cells).map(([k, c]) => [k, c.state]))])),
  } as { version: number; tute: Record<string, Record<string, string>> };
}

/** Toggles cells in Staff -> Permissions (desktop) and saves through the confirmation dialog. */
async function saveCellsInUi(page: Page, cells: { label: string; role: string; checked: boolean }[]) {
  await page.goto(`${TENANT_BASE}/tenant-admin/staff?tab=permissions`);
  await expect(page.getByRole("region", { name: "Permission matrix" })).toBeVisible({ timeout: 20_000 });
  for (const cell of cells) {
    const box = page.getByRole("checkbox", { name: `${cell.label} — ${cell.role}` });
    await box.scrollIntoViewIfNeeded();
    if ((await box.isChecked()) !== cell.checked) await box.click();
  }
  await page.getByRole("button", { name: "Save changes" }).click();
  const confirm = page.getByRole("alertdialog");
  await expect(confirm).toBeVisible();
  await confirm.getByRole("button").last().click();
  await expect(confirm).toBeHidden({ timeout: 15_000 });
  await page.reload();
  for (const cell of cells) {
    const box = page.getByRole("checkbox", { name: `${cell.label} — ${cell.role}` });
    if (cell.checked) await expect(box).toBeChecked({ timeout: 20_000 });
    else await expect(box).not.toBeChecked({ timeout: 20_000 });
  }
}

test.beforeAll(() => {
  test.skip(!ids, "run e2e/tute-live-api.spec.ts first (live-ids.json missing)");
  fs.mkdirSync(EVIDENCE, { recursive: true });
});

test.afterAll(async () => {
  if (!ids) return;
  const baseline = JSON.parse(fs.readFileSync(path.join(EVIDENCE, "matrix-baseline.json"), "utf-8")).tute as Record<string, Record<string, string>>;
  const current = await tuteMatrix();
  const changes = Object.entries(current.tute).flatMap(([cap, cells]) =>
    Object.entries(cells)
      .filter(([role, state]) => state !== baseline[cap]?.[role] && (state === "GRANTED" || state === "NOT_GRANTED"))
      .map(([roleCode]) => ({ roleCode, capabilityKey: cap, granted: baseline[cap][roleCode] === "GRANTED" }))
  );
  if (changes.length) {
    const ctx = await request.newContext();
    const token = await apiLogin(ctx, ADMIN_EMAIL);
    await api(ctx, token, "PUT", "/staff/permission-matrix", { expectedVersion: current.version, changes });
    await ctx.dispose();
  }
  const after = await tuteMatrix();
  report.matrixRestored = JSON.stringify(after.tute) === JSON.stringify(baseline);
  report.matrixVersionAfter = after.version;
  fs.writeFileSync(path.join(EVIDENCE, "ui-report.json"), JSON.stringify(report, null, 2));
});

for (const vp of VIEWPORTS) {
  test(`A9 Tenant Admin tute screens — ${vp.name}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height });
    await loginUi(page, ADMIN_EMAIL);
    const over: Record<string, number> = {};
    const c1 = ids.courses.C1;
    const cov = ids.tutes.cov;

    // Course list, More menu open on the run's course.
    await page.goto(`${TENANT_BASE}/tenant-admin/courses`);
    const search = page.getByRole("searchbox", { name: "Search courses" });
    await expect(search).toBeVisible({ timeout: 20_000 });
    await search.fill(c1.name);
    // Desktop renders a table row, mobile a card: both expose the same labelled actions trigger.
    const trigger = page.getByRole("button", { name: `Actions for ${c1.name}` }).locator("visible=true").first();
    await expect(trigger).toBeVisible({ timeout: 20_000 });
    await trigger.click();
    const menu = page.getByTestId("course-actions-menu-content");
    await expect(menu.getByTestId("course-action-tutes")).toBeVisible();
    await shot(page, `${vp.name}-01-course-more-menu`);
    over.courses = await overflow(page);
    await menu.getByTestId("course-action-tutes").click();

    // Tutes of [Course].
    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${c1.id}/tutes`));
    await expect(page.getByRole("heading", { level: 1, name: `Tutes of ${c1.name}` })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId("tute-row").first()).toBeVisible();
    await shot(page, `${vp.name}-02-tutes-of-course`);
    over.tutes = await overflow(page);

    // Add Tute (empty, then Paid with Price) — cancelled, nothing saved.
    await page.getByTestId("tutes-add").click();
    let dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await shot(page, `${vp.name}-03-add-tute-empty`);
    await dialog.getByLabel("Tute Name").fill("Preview only – not saved");
    await dialog.getByLabel("Quantity").fill("25");
    await dialog.getByLabel("Payment Type").selectOption("PAID");
    await dialog.getByLabel(/Price/).fill("450");
    await shot(page, `${vp.name}-04-add-tute-paid-price`);
    over.addDialog = await overflow(page);
    await dialog.getByTestId("tute-form-cancel").click();
    await expect(dialog).toBeHidden();

    // Edit dialog (Save Changes) — cancelled.
    const covRow = page.getByTestId("tute-row").filter({ hasText: cov.name }).first();
    await covRow.getByTestId("tute-action-edit").click();
    dialog = page.getByRole("dialog");
    await expect(dialog.getByRole("button", { name: "Save Changes" })).toBeVisible();
    await expect(dialog.getByLabel("Tute Name")).toHaveValue(cov.name);
    await shot(page, `${vp.name}-05-edit-tute`);
    await dialog.getByTestId("tute-form-cancel").click();

    // Tute detail.
    await page.goto(`${TENANT_BASE}/tenant-admin/courses/${c1.id}/tutes/${cov.id}`);
    await expect(page.getByRole("heading", { level: 1, name: new RegExp(cov.name) })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId("fulfillment-row").first()).toBeVisible();
    await shot(page, `${vp.name}-06-tute-detail`);
    over.detail = await overflow(page);

    // Tute Dispatch queue with selection banner.
    await page.goto(`${TENANT_BASE}/tenant-admin/tute-dispatch`);
    await expect(page.getByRole("heading", { level: 1, name: "Tute Dispatch" })).toBeVisible({ timeout: 20_000 });
    await expect(page.getByTestId("dispatch-row").first()).toBeVisible({ timeout: 20_000 });
    await page.getByTestId("dispatch-select-page").check();
    await expect(page.getByTestId("dispatch-selection-banner")).toBeVisible();
    await shot(page, `${vp.name}-07-dispatch-selection`);
    over.dispatch = await overflow(page);
    report[`${vp.name}DispatchBanner`] = await page.getByTestId("dispatch-selection-banner").innerText();
    report[`${vp.name}PrintButton`] = await page.getByTestId("dispatch-print").innerText();

    // Staff permissions — Tute group.
    await page.goto(`${TENANT_BASE}/tenant-admin/staff?tab=permissions`);
    await expect(page.getByRole("region", { name: "Permission matrix" })).toBeVisible({ timeout: 20_000 });
    const first = page.getByRole("checkbox", { name: "View Tutes — Operator" });
    await first.scrollIntoViewIfNeeded();
    await page.evaluate(() => window.scrollBy(0, 120));
    await shot(page, `${vp.name}-08-permissions-tute-group`);
    over.permissions = await overflow(page);
    report[`${vp.name}Overflow`] = over;
  });
}

test("A7 Operator grant/revoke through Staff -> Permissions UI", async ({ page, browser }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await loginUi(page, ADMIN_EMAIL);
  await saveCellsInUi(page, [
    { label: "View Tute Dispatch", role: "Operator", checked: true },
    { label: "Print Dispatch Labels", role: "Operator", checked: false },
  ]);
  report.matrixAfterGrantView = (await tuteMatrix()).tute.TUTE_DISPATCH_VIEW.OPERATOR;

  const opContext = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const op = await opContext.newPage();
  await loginUi(op, ids.operatorEmail);
  const nav = op.getByRole("navigation", { name: "Primary" });
  await expect(nav.getByRole("link", { name: "Tute Dispatch" })).toBeVisible({ timeout: 20_000 });
  await nav.getByRole("link", { name: "Tute Dispatch" }).click();
  await expect(op.getByTestId("dispatch-row").first()).toBeVisible({ timeout: 20_000 });
  report.operatorViewOnly = { print: await op.getByTestId("dispatch-print").count(), confirm: await op.getByTestId("dispatch-confirm").count() };
  expect(report.operatorViewOnly).toEqual({ print: 0, confirm: 0 });
  await shot(op, "desktop-09-operator-dispatch-view-only");

  // Grant PRINT_LABELS -> print works.
  await saveCellsInUi(page, [{ label: "Print Dispatch Labels", role: "Operator", checked: true }]);
  await op.reload();
  const firstRow = op.getByTestId("dispatch-row").filter({ hasNot: op.getByTestId("dispatch-no-address") }).first();
  await expect(firstRow).toBeVisible({ timeout: 20_000 });
  await firstRow.getByTestId("dispatch-row-checkbox").check();
  await expect(op.getByTestId("dispatch-print")).toBeVisible();
  await shot(op, "desktop-10-operator-print-granted");
  const printed = op.waitForResponse((r) => r.url().includes("/tute-dispatch/labels") && r.request().method() === "POST");
  await op.getByTestId("dispatch-print").click();
  const missingDialog = op.getByTestId("dispatch-missing-address-dialog");
  if (await missingDialog.isVisible().catch(() => false)) await op.getByTestId("dispatch-missing-address-print").click();
  report.operatorPrintAfterGrant = (await printed).status();
  expect(report.operatorPrintAfterGrant).toBe(200);

  // Revoke PRINT_LABELS while the Operator page stays open: the next print on the same session -> 403.
  await saveCellsInUi(page, [{ label: "Print Dispatch Labels", role: "Operator", checked: false }]);
  const denied = op.waitForResponse((r) => r.url().includes("/tute-dispatch/labels") && r.request().method() === "POST");
  await op.getByTestId("dispatch-print").click();
  if (await missingDialog.isVisible().catch(() => false)) await op.getByTestId("dispatch-missing-address-print").click();
  report.operatorPrintAfterRevokeSameSession = (await denied).status();
  expect(report.operatorPrintAfterRevokeSameSession).toBe(403);
  await shot(op, "desktop-11-operator-print-revoked-same-session");
  await op.reload();
  await expect(op.getByTestId("dispatch-row").first()).toBeVisible({ timeout: 20_000 });
  report.operatorPrintAfterRefresh = await op.getByTestId("dispatch-print").count();
  expect(report.operatorPrintAfterRefresh).toBe(0);
  await shot(op, "desktop-12-operator-after-refresh");
  await opContext.close();

  // Operator nav on mobile (view still granted).
  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 } });
  const mp = await mobile.newPage();
  await loginUi(mp, ids.operatorEmail);
  await mp.goto(`${TENANT_BASE}/tenant-admin/tute-dispatch`);
  await expect(mp.getByTestId("dispatch-row").first()).toBeVisible({ timeout: 20_000 });
  report.operatorMobileOverflow = await overflow(mp);
  await shot(mp, "mobile-09-operator-dispatch-view-only");
  await mobile.close();

  // Revoke VIEW through the UI as well (the afterAll restore is a safety net).
  await saveCellsInUi(page, [{ label: "View Tute Dispatch", role: "Operator", checked: false }]);
});

test("A9 render label PDFs (ours + Klass reference) to PNG", async () => {
  const m2 = path.join(os.homedir(), ".m2", "repository");
  const jar = (group: string, artifact: string, version: string) => path.join(m2, ...group.split("."), artifact, version, `${artifact}-${version}.jar`);
  const cp = [
    jar("org.apache.pdfbox", "pdfbox", "3.0.4"),
    jar("org.apache.pdfbox", "fontbox", "3.0.4"),
    jar("org.apache.pdfbox", "pdfbox-io", "3.0.4"),
    jar("commons-logging", "commons-logging", "1.3.5"),
  ].join(path.delimiter);
  const tool = path.resolve(__dirname, "tools/RenderPdf.java");
  const inputs: [string, string][] = [
    [path.join(EVIDENCE, "labels-small.pdf"), path.join(EVIDENCE, "pdf-labels-small")],
    [path.join(EVIDENCE, "labels-large.pdf"), path.join(EVIDENCE, "pdf-labels-large")],
    // The Klass reference contains third-party sample names/phones: render it to the OS temp dir only, never into evidence.
    [path.join(REFERENCES, "Tute_Dispatch_Labels.pdf"), path.join(os.tmpdir(), "tute-klass-reference")],
  ];
  const out: Record<string, string> = {};
  for (const [input, prefix] of inputs) {
    test.skip(!fs.existsSync(input), `${input} missing`);
    out[path.basename(prefix)] = execFileSync("java", ["-cp", cp, tool, input, prefix, "110"], { encoding: "utf-8" }).trim();
  }
  report.pdfRender = out;
  expect(out["pdf-labels-small"]).toContain("pages=1");
});
