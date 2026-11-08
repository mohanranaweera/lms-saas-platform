import { test, expect, request, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { ADMIN_EMAIL, TENANT_BASE, api, apiLogin, loginUi } from "./fixtures/klass/klass-api";

/**
 * STAFF-MGMT-02 live evidence against the real local stack (opt-in: `STAFF_LIVE=1`).
 * Captures Users / Add User / Permissions (unsaved + save summary) / Activities (expanded)
 * at 1440x900 and 390x844, and the Operator's navigation with only granted areas, into
 * docs/qa/staff-management-evidence/. Requires an existing synthetic Operator in the demo
 * tenant whose email is given by STAFF_LIVE_OPERATOR_EMAIL (created by the API verification
 * run through Add User + account-setup email). Every grant this spec saves is revoked in
 * afterAll, so the tenant's matrix ends where it started.
 */
test.skip(!process.env.STAFF_LIVE, "live backend check; set STAFF_LIVE=1");
test.describe.configure({ mode: "serial" });

const EVIDENCE = path.resolve(__dirname, "../../docs/qa/staff-management-evidence");
const OPERATOR_EMAIL = process.env.STAFF_LIVE_OPERATOR_EMAIL ?? "";
const GRANTS = ["COURSES_VIEW", "COURSES_ADD", "STUDENTS_VIEW"] as const;
const VIEWPORTS = [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 },
] as const;

async function shot(page: Page, name: string, settleMs = 400) {
  await page.waitForTimeout(settleMs); // let dialog/tab animations settle (evidence only)
  fs.mkdirSync(EVIDENCE, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE, `${name}.png`), fullPage: false });
}

async function horizontalOverflow(page: Page) {
  return page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
}

async function setOperatorGrants(granted: boolean) {
  const ctx = await request.newContext();
  const token = await apiLogin(ctx, ADMIN_EMAIL);
  const matrix = await api<{ version: number }>(ctx, token, "GET", "/staff/permission-matrix");
  const result = await api(ctx, token, "PUT", "/staff/permission-matrix", {
    expectedVersion: matrix.body.data!.version,
    changes: GRANTS.map((capabilityKey) => ({ roleCode: "OPERATOR", capabilityKey, granted })),
  });
  await ctx.dispose();
  return result.status;
}

test.afterAll(async () => {
  expect(await setOperatorGrants(false)).toBe(200);
});

for (const vp of VIEWPORTS) {
  test(`Tenant Admin staff screens — ${vp.name}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height });
    await loginUi(page, ADMIN_EMAIL);
    const overflow: Record<string, number> = {};

    await page.goto(`${TENANT_BASE}/tenant-admin/staff`);
    await expect(page.getByRole("heading", { name: "Staff" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Add User" }).first()).toBeVisible({ timeout: 20_000 });
    await shot(page, `${vp.name}-01-users`);
    overflow.users = await horizontalOverflow(page);

    await page.getByRole("button", { name: "Add User" }).first().click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await shot(page, `${vp.name}-02-add-user-dialog`);
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();

    await page.goto(`${TENANT_BASE}/tenant-admin/staff?tab=permissions`);
    const matrix = page.getByRole("region", { name: "Permission matrix" });
    await expect(matrix).toBeVisible({ timeout: 20_000 });
    if (vp.name === "mobile") await page.locator("#permissions-role-select").selectOption("OPERATOR");
    for (const key of GRANTS) {
      const cell = page.getByTestId(`cell-${key}-OPERATOR`);
      await cell.scrollIntoViewIfNeeded();
      await cell.click(); // toggle: works whether the previous viewport saved the grants or not
    }
    await expect(page.getByTestId("unsaved-count")).toContainText(`${GRANTS.length} unsaved`);
    await page.getByTestId("unsaved-count").scrollIntoViewIfNeeded();
    await shot(page, `${vp.name}-03-permissions-unsaved`);
    overflow.permissions = await horizontalOverflow(page);
    await page.getByRole("button", { name: "Save changes" }).click();
    const confirm = page.getByRole("alertdialog");
    await expect(confirm).toBeVisible();
    await expect(page.getByTestId("save-summary")).toContainText("Operator");
    await shot(page, `${vp.name}-04-permissions-save-summary`);
    if (vp.name === "desktop") {
      await confirm.getByRole("button").last().click();
      await expect(confirm).toBeHidden({ timeout: 15_000 });
      await expect(page.getByTestId("unsaved-count")).toHaveCount(0);
      await page.reload();
      await expect(page.getByTestId(`cell-COURSES_ADD-OPERATOR`)).toBeChecked({ timeout: 20_000 });
    } else {
      await confirm.getByRole("button").first().click();
      await page.getByRole("button", { name: "Discard" }).click();
    }

    await page.goto(`${TENANT_BASE}/tenant-admin/staff?tab=activities`);
    const details = page.getByRole("button", { name: "Details" }).first();
    await expect(details).toBeVisible({ timeout: 20_000 });
    await details.click();
    await expect(details).toHaveAttribute("aria-expanded", "true");
    await shot(page, `${vp.name}-05-activities-expanded`);
    overflow.activities = await horizontalOverflow(page);

    fs.writeFileSync(path.join(EVIDENCE, `${vp.name}-overflow.json`), JSON.stringify(overflow, null, 2));
  });
}

test("Operator navigation shows only granted areas", async ({ browser }) => {
  test.skip(!OPERATOR_EMAIL, "set STAFF_LIVE_OPERATOR_EMAIL");
  expect(await setOperatorGrants(true)).toBe(200);
  const report: Record<string, unknown> = {};
  for (const vp of VIEWPORTS) {
    const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
    const page = await context.newPage();
    await loginUi(page, OPERATOR_EMAIL);
    await page.waitForLoadState("networkidle");
    if (vp.name === "mobile") {
      const menu = page.getByRole("button", { name: /menu|navigation/i }).first();
      if (await menu.isVisible().catch(() => false)) await menu.click();
    }
    const nav = page.getByRole("navigation").first();
    await expect(nav).toBeVisible({ timeout: 20_000 });
    const links = await page.getByRole("navigation").getByRole("link").allInnerTexts();
    report[vp.name] = { url: page.url(), links: links.map((l) => l.trim()).filter(Boolean) };
    await shot(page, `${vp.name}-06-operator-nav`);
    await context.close();
  }
  fs.writeFileSync(path.join(EVIDENCE, "operator-nav.json"), JSON.stringify(report, null, 2));
  const desktopLinks = (report.desktop as { links: string[] }).links.join(" | ");
  expect(desktopLinks).not.toMatch(/\bStaff\b/);
  expect(desktopLinks).not.toMatch(/\bFinance\b/);
});
