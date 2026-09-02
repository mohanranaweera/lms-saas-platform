import { test, expect } from "@playwright/test";
import { apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * `LogoutControl` (components/auth/logout-control.tsx) coverage: Tenant Admin
 * and Platform Admin require a confirmation `AlertDialog` before signing out;
 * Student and Teacher sign out immediately with no confirmation step (plan
 * §11, wired via each role layout's `logout={{ kind, requireConfirmation }}`
 * — see `dashboard-shell.tsx` and the four `(role)/layout.tsx` files).
 *
 * Dashboards are reached by direct navigation (same pattern as
 * `route-groups.spec.ts`) with the refresh/logout endpoints mocked per
 * `fixtures/auth-mocks.ts` (no backend in this environment). As of MVP-006
 * Student Management and MVP-014 Teacher Dashboard, `/tenant-admin/dashboard`,
 * `/student/dashboard`, and `/teacher/dashboard` are all behind `RouteGuard`
 * (`ensureAccessToken` on mount), so every test reaching one of those three
 * now mocks `POST .../v1/auth/refresh` before navigating; only
 * `/platform-admin/dashboard` remains unguarded.
 */

function mockSuccessfulLogoutFlow(page: import("@playwright/test").Page, role: string) {
  const token = fakeJwt({ role });
  return mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)))
    .then(() => mockJson(page, "**/v1/auth/logout", 200, apiSuccess(null)))
    .then(() =>
      // `/teacher/dashboard` (MVP-014) fetches its own course list — mock it
      // so the page settles deterministically; the Student dashboard's own
      // reads are separately mocked per test below.
      mockJson(
        page,
        "**/v1/courses*",
        200,
        apiSuccess({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })
      )
    );
}

test.describe("logout confirmation — Tenant Admin (requires confirmation)", () => {
  test("opens a focus-trapped alertdialog and returns focus to the trigger on cancel", async ({
    page,
  }) => {
    // `/tenant-admin/dashboard` is guarded by `RouteGuard` (MVP-006 Student
    // Management) — mock a successful silent refresh so the guard resolves.
    const guardToken = fakeJwt({ role: "TENANT_ADMIN" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(guardToken)));

    await page.goto("/tenant-admin/dashboard");

    const trigger = page.getByRole("button", { name: "Sign out" });
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await expect(
      dialog.getByRole("heading", { name: "Sign out of Tenant Admin?" })
    ).toBeVisible();

    // Focus starts inside the dialog and stays there while tabbing well past
    // its number of focusable elements (Cancel, Sign out).
    for (let i = 0; i < 5; i++) {
      await page.keyboard.press("Tab");
      await expect(dialog.locator(":focus")).toHaveCount(1);
    }

    await dialog.getByRole("button", { name: "Cancel" }).click();

    await expect(dialog).not.toBeVisible();
    await expect(trigger).toBeFocused();
    // Cancelling must not navigate away or sign the user out.
    await expect(page).toHaveURL(/\/tenant-admin\/dashboard$/);
  });

  test("confirming calls the logout endpoint and redirects to /login", async ({ page }) => {
    const token = fakeJwt({ role: "TENANT_ADMIN" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));

    let logoutCalled = false;
    await page.route("**/v1/auth/logout", async (route) => {
      logoutCalled = true;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(null)),
      });
    });

    await page.goto("/tenant-admin/dashboard");
    await page.getByRole("button", { name: "Sign out" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Sign out" }).click();

    await expect(page).toHaveURL(/\/login$/);
    expect(logoutCalled).toBe(true);
  });

  test("a failed server-side revoke surfaces an alert and does not silently redirect", async ({
    page,
  }) => {
    const token = fakeJwt({ role: "TENANT_ADMIN" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
    await page.route("**/v1/auth/logout", async (route) => {
      await route.fulfill({ status: 500, contentType: "application/json", body: "{}" });
    });

    await page.goto("/tenant-admin/dashboard");
    await page.getByRole("button", { name: "Sign out" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Sign out" }).click();

    // Local state is still cleared, but the failure must be visible, not
    // treated as an ordinary sign-out — no silent redirect.
    await expect(dialog.getByRole("alert")).toBeVisible();
    await expect(page).toHaveURL(/\/tenant-admin\/dashboard$/);
    await expect(dialog.getByRole("link", { name: "Continue to sign-in" })).toBeVisible();
  });
});

test.describe("logout confirmation — Platform Admin (requires confirmation)", () => {
  test("confirming calls the platform-admin logout endpoint and redirects to /platform-admin/login", async ({
    page,
  }) => {
    const token = fakeJwt({ role: "PLATFORM_ADMIN" });
    await mockJson(
      page,
      "**/v1/platform-admin/auth/refresh",
      200,
      apiSuccess(refreshResponseBody(token))
    );

    let logoutCalled = false;
    await page.route("**/v1/platform-admin/auth/logout", async (route) => {
      logoutCalled = true;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(null)),
      });
    });

    await page.goto("/platform-admin/dashboard");
    const trigger = page.getByRole("button", { name: "Sign out" });
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Sign out" }).click();

    await expect(page).toHaveURL(/\/platform-admin\/login$/);
    expect(logoutCalled).toBe(true);
  });

  test("cancelling leaves the platform admin on the dashboard, no logout call made", async ({
    page,
  }) => {
    let logoutCalled = false;
    await page.route("**/v1/platform-admin/auth/logout", async (route) => {
      logoutCalled = true;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(null)),
      });
    });

    await page.goto("/platform-admin/dashboard");
    const trigger = page.getByRole("button", { name: "Sign out" });
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Cancel" }).click();

    await expect(dialog).not.toBeVisible();
    await expect(trigger).toBeFocused();
    await expect(page).toHaveURL(/\/platform-admin\/dashboard$/);
    expect(logoutCalled).toBe(false);
  });
});

test.describe("logout — Student/Teacher (no confirmation step)", () => {
  const cases: Array<{ dashboard: string; loginPath: RegExp }> = [
    { dashboard: "/student/dashboard", loginPath: /\/login$/ },
    { dashboard: "/teacher/dashboard", loginPath: /\/login$/ },
  ];

  for (const { dashboard, loginPath } of cases) {
    test(`${dashboard}: signing out never shows a confirmation dialog`, async ({ page }) => {
      await mockSuccessfulLogoutFlow(page, "STUDENT");

      await page.goto(dashboard);
      await page.getByRole("button", { name: "Sign out" }).click();

      // No alertdialog ever appears for these roles.
      await expect(page.getByRole("alertdialog")).toHaveCount(0);
      await expect(page).toHaveURL(loginPath);
    });
  }
});
