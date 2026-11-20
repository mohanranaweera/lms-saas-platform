import { test, expect, request, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { ADMIN_EMAIL, TENANT_BASE, api, apiLogin, loginUi } from "./fixtures/klass/klass-api";

/**
 * STAFF-MGMT-03 live evidence against the real local stack (opt-in: `STAFF_LIVE=1`).
 * Permissions with "Roles shown" = all 10, Add User role list + role guide descriptions, and the
 * Finance Staff navigation before/after a revoke, at 1440x900 and 390x844, into
 * docs/qa/staff-management-evidence/ (prefix `m03-`). Requires a synthetic Finance Staff user
 * (STAFF_LIVE_FINANCE_EMAIL) created through Add User + account setup. The revokes this spec
 * makes are re-granted in afterAll (Finance Staff's static defaults), leaving the effective
 * matrix as it started.
 */
test.skip(!process.env.STAFF_LIVE, "live backend check; set STAFF_LIVE=1");
test.describe.configure({ mode: "serial" });

const EVIDENCE = path.resolve(__dirname, "../../docs/qa/staff-management-evidence");
const FINANCE_EMAIL = process.env.STAFF_LIVE_FINANCE_EMAIL ?? "";
const REVOKED = ["FINANCE_VIEW_EXPENSES", "TEACHERS_VIEW_WALLET"] as const;
const VIEWPORTS = [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 },
] as const;

async function shot(page: Page, name: string, settleMs = 400) {
  await page.waitForTimeout(settleMs); // let popover/dialog animations settle (evidence only)
  fs.mkdirSync(EVIDENCE, { recursive: true });
  await page.screenshot({ path: path.join(EVIDENCE, `m03-${name}.png`), fullPage: false });
}

async function setFinance(granted: boolean) {
  const ctx = await request.newContext();
  const token = await apiLogin(ctx, ADMIN_EMAIL);
  const matrix = await api<{ version: number }>(ctx, token, "GET", "/staff/permission-matrix");
  const result = await api(ctx, token, "PUT", "/staff/permission-matrix", {
    expectedVersion: matrix.body.data!.version,
    changes: REVOKED.map((capabilityKey) => ({ roleCode: "FINANCE_STAFF", capabilityKey, granted })),
  });
  await ctx.dispose();
  return result.status;
}

async function navLinks(page: Page) {
  return (await page.getByRole("navigation").getByRole("link").allInnerTexts()).map((l) => l.trim()).filter(Boolean);
}

test.afterAll(async () => {
  expect(await setFinance(true)).toBe(200);
});

for (const vp of VIEWPORTS) {
  test(`Tenant Admin STAFF-MGMT-03 screens — ${vp.name}`, async ({ page }) => {
    await page.setViewportSize({ width: vp.width, height: vp.height });
    await loginUi(page, ADMIN_EMAIL);
    const report: Record<string, unknown> = {};

    await page.goto(`${TENANT_BASE}/tenant-admin/staff?tab=permissions`);
    await expect(page.getByRole("region", { name: "Permission matrix" })).toBeVisible({ timeout: 20_000 });
    if (vp.name === "desktop") {
      const help = page.locator("#permissions-roles-shown-help");
      report.rolesShownDefault = (await help.innerText()).split(".")[0];
      await shot(page, `${vp.name}-01-permissions-default-roles`);
      if (!/10 of 10/.test(await help.innerText())) {
        await page.getByTestId("roles-shown").click();
        const popup = page.getByTestId("roles-shown-popup");
        await expect(popup).toBeVisible();
        await popup.getByText("All", { exact: true }).click();
        await shot(page, `${vp.name}-02-roles-shown-popup`);
        await page.keyboard.press("Escape");
      }
      await expect(help).toContainText("10 of 10 roles shown");
      const headers = await page.getByRole("region", { name: "Permission matrix" }).locator("thead th").allInnerTexts();
      report.columnHeaders = headers.map((h) => h.trim()).filter(Boolean);
      await shot(page, `${vp.name}-03-permissions-all-10-roles`);
      const scroll = page.getByTestId("permission-matrix-scroll");
      report.matrixScroll = await scroll.evaluate((el) => ({ scrollWidth: el.scrollWidth, clientWidth: el.clientWidth }));
      await scroll.evaluate((el) => el.scrollTo({ left: el.scrollWidth }));
      await shot(page, `${vp.name}-04-permissions-scrolled-right`);
    } else {
      const select = page.locator("#permissions-role-select");
      report.mobileRoleOptions = await select.locator("option").allInnerTexts();
      await select.selectOption("FINANCE_STAFF");
      await shot(page, `${vp.name}-03-permissions-finance-staff-column`);
    }
    report.pageOverflowPx = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);

    await page.goto(`${TENANT_BASE}/tenant-admin/staff`);
    await page.getByRole("button", { name: "Add User" }).first().click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    const roleSelect = dialog.locator("#add-user-role");
    await expect(roleSelect.locator("option")).toHaveCount(10, { timeout: 15_000 }); // placeholder + 9
    report.addUserRoles = (await roleSelect.locator("option").allInnerTexts()).slice(1);
    await roleSelect.selectOption("FINANCE_STAFF");
    report.selectedRoleHelper = await dialog.locator("#add-user-role-helper, [id^='add-user-role'][id$='helper']").first().innerText().catch(() => null);
    const guide = dialog.getByTestId("role-guide");
    await guide.locator("summary").click();
    report.roleGuide = await guide.locator("dt").allInnerTexts();
    report.roleGuideDescriptions = await guide.locator("dd").allInnerTexts();
    await guide.scrollIntoViewIfNeeded();
    await shot(page, `${vp.name}-05-add-user-role-guide`);
    fs.writeFileSync(path.join(EVIDENCE, `m03-${vp.name}-report.json`), JSON.stringify(report, null, 2));
  });
}

test("Finance Staff navigation before and after a revoke", async ({ browser }) => {
  test.skip(!FINANCE_EMAIL, "set STAFF_LIVE_FINANCE_EMAIL");
  const report: Record<string, unknown> = {};
  for (const phase of ["before", "after"] as const) {
    if (phase === "after") expect(await setFinance(false)).toBe(200);
    for (const vp of VIEWPORTS) {
      const context = await browser.newContext({ viewport: { width: vp.width, height: vp.height } });
      const page = await context.newPage();
      await loginUi(page, FINANCE_EMAIL);
      await page.waitForLoadState("networkidle");
      if (vp.name === "mobile") {
        const menu = page.getByRole("button", { name: /menu|navigation/i }).first();
        if (await menu.isVisible().catch(() => false)) await menu.click();
      }
      await expect(page.getByRole("navigation").first()).toBeVisible({ timeout: 20_000 });
      report[`${phase}-${vp.name}`] = await navLinks(page);
      await shot(page, `${vp.name}-06-finance-staff-nav-${phase}-revoke`);
      await context.close();
    }
  }
  fs.writeFileSync(path.join(EVIDENCE, "m03-finance-staff-nav.json"), JSON.stringify(report, null, 2));
  const before = (report["before-desktop"] as string[]).join(" | ");
  const after = (report["after-desktop"] as string[]).join(" | ");
  expect(before).toMatch(/Expenses/);
  expect(after).not.toMatch(/\bExpenses\b/);
  expect(after).not.toMatch(/Teacher Payouts/);
  expect(after).not.toMatch(/\bStaff\b/);
});
