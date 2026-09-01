import { test, expect } from "@playwright/test";
import { apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

test.describe("public route group", () => {
  test("home page renders marketing content and auth entry points", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await expect(page.getByRole("link", { name: "Sign in" }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Get started" }).first()).toBeVisible();
  });

  test("both 'Get started' entry points link to the tenant self-registration screen, not the disabled account placeholder", async ({
    page,
  }) => {
    // "Get started" appears twice on the home page — once in the header nav,
    // once as the hero CTA — and both now point at the real, working
    // tenant-registration flow rather than the unrelated `(auth)/register`
    // disabled placeholder. Previously the header nav had a *second*,
    // differently-worded link ("Register your institute") to the same
    // destination sitting next to a "Get started" that went to the dead
    // placeholder — ambiguous and half-broken. Now there's exactly one
    // meaning for "Get started", consistently, everywhere it appears.
    await page.goto("/");
    const links = page.getByRole("link", { name: "Get started" });
    await expect(links).toHaveCount(2);

    for (let i = 0; i < 2; i += 1) {
      await page.goto("/");
      await links.nth(i).click();
      await expect(page).toHaveURL(/\/register-institute$/);
      await expect(page.getByRole("heading", { name: "Register your institute" })).toBeVisible();
    }
  });
});

test.describe("auth route group", () => {
  test("login page renders a real, submittable sign-in form", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByLabel("Email")).toBeEnabled();
    await expect(page.getByLabel("Password", { exact: true })).toBeEnabled();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeEnabled();
    // Password show/hide toggle exposes a stateful, accessible label.
    const toggle = page.getByRole("button", { name: "Show password" });
    await expect(toggle).toBeVisible();
    await toggle.click();
    await expect(page.getByRole("button", { name: "Hide password" })).toBeVisible();
  });

  test("login page shows the session-expired banner when redirected with that reason", async ({
    page,
  }) => {
    await page.goto("/login?reason=session_expired");
    await expect(page.getByText("Your session has expired")).toBeVisible();
  });

  test("register page renders a disabled placeholder form", async ({ page }) => {
    await page.goto("/register");
    await expect(page.getByText("Not yet implemented", { exact: false })).toBeVisible();
    await expect(page.getByRole("button", { name: "Create account" })).toBeDisabled();
  });

  test("forgot-password page renders a disabled placeholder form", async ({ page }) => {
    await page.goto("/forgot-password");
    await expect(page.getByText("Not yet implemented", { exact: false })).toBeVisible();
    await expect(page.getByRole("button", { name: "Send reset link" })).toBeDisabled();
  });
});

test.describe("platform-admin login route", () => {
  test("renders a real sign-in form with no dashboard chrome", async ({ page }) => {
    await page.goto("/platform-admin/login");
    await expect(page.getByLabel("Email")).toBeEnabled();
    await expect(page.getByLabel("Password", { exact: true })).toBeEnabled();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeEnabled();
    // No dashboard shell on the login route: no primary nav landmark, no logout control.
    await expect(page.getByRole("navigation", { name: "Primary" })).toHaveCount(0);
  });
});

test.describe("role dashboard route groups", () => {
  const dashboards: Array<{ path: string; heading: string }> = [
    // `/student/dashboard`'s heading is "Overview" as of MVP-013 (Student
    // Dashboard, SDASH-1) — the other three role dashboards remain the
    // original static "<Role> Dashboard" placeholder this test was written
    // for.
    { path: "/student/dashboard", heading: "Overview" },
    { path: "/teacher/dashboard", heading: "Teacher Dashboard" },
    { path: "/tenant-admin/dashboard", heading: "Tenant Admin Dashboard" },
    { path: "/platform-admin/dashboard", heading: "Platform Admin Dashboard" },
  ];

  test.beforeEach(async ({ page }) => {
    // `/student/dashboard` and `/tenant-admin/dashboard` are guarded by
    // `RouteGuard` (MVP-006 Student Management) — mock a successful silent
    // refresh so the guard resolves for those two; `/teacher/dashboard` and
    // `/platform-admin/dashboard` remain unguarded and ignore this mock.
    const token = fakeJwt({ role: "STUDENT" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
    // `/student/dashboard` is a real data-driven page as of MVP-013 — mock
    // its three reads so it renders deterministically; the other three
    // dashboards are still static placeholders and ignore these mocks.
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));
  });

  for (const { path, heading } of dashboards) {
    test(`${path} renders its portal shell${path === "/student/dashboard" ? "" : " and placeholder dashboard"}`, async ({
      page,
    }) => {
      // `(tenant-admin)` is wrapped in `RouteGuard` (MVP-007), which calls
      // ensureAccessToken("tenant") on mount — mock a successful refresh so
      // the guard resolves instead of redirecting to /login. The other three
      // portals have no route guard yet and are reached by direct navigation.
      if (path.startsWith("/tenant-admin")) {
        const token = fakeJwt({ role: "TENANT_ADMIN" });
        await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
      }
      await page.goto(path);
      await expect(page.getByRole("heading", { name: heading })).toBeVisible();
      await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Dashboard" })).toBeVisible();
    });
  }

  test("platform admin nav links to the tenant list scaffold", async ({ page }) => {
    await page.goto("/platform-admin/dashboard");
    const link = page.getByRole("link", { name: "Tenants" });
    await expect(link).toBeVisible();
    await link.click();
    await expect(page).toHaveURL(/\/platform-admin\/tenants$/);
    await expect(page.getByRole("heading", { name: "Tenants" })).toBeVisible();
  });
});

test.describe("not-found", () => {
  test("unknown route renders the not-found page", async ({ page }) => {
    await page.goto("/this-route-does-not-exist");
    await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to home" })).toBeVisible();
  });
});
