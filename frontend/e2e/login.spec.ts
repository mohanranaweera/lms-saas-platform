import { test, expect } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, loginResponseBody, mockJson } from "./fixtures/auth-mocks";

/**
 * Deeper login coverage for `LoginForm` (components/auth/login-form.tsx),
 * wired to `POST /v1/auth/login` and `POST /v1/platform-admin/auth/login`
 * (docs/api/identity-access-service.md). `route-groups.spec.ts` and
 * `accessibility.spec.ts` already cover static rendering (labeled fields,
 * session-expired banner visibility, no-dashboard-chrome on the platform-admin
 * login route) — this file is scoped to the request/response behavior those
 * specs don't exercise: real submissions against a mocked backend.
 *
 * No backend runs in this environment (see fixtures/auth-mocks.ts) — every
 * test here intercepts the request with `page.route()`.
 */

async function submitLogin(page: import("@playwright/test").Page) {
  await page.getByLabel("Email").fill("user@example.com");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();
}

test.describe("tenant login — role-based dashboard redirect", () => {
  const cases: Array<{ role: string; path: string; heading: string }> = [
    { role: "TENANT_ADMIN", path: "/tenant-admin/dashboard", heading: "Tenant Admin Dashboard" },
    { role: "TEACHER", path: "/teacher/dashboard", heading: "Teacher Dashboard" },
    { role: "STUDENT", path: "/student/dashboard", heading: "Student Dashboard" },
  ];

  for (const { role, path, heading } of cases) {
    test(`${role} login redirects to ${path}`, async ({ page }) => {
      const token = fakeJwt({ role });
      await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(token)));

      await page.goto("/login");
      await submitLogin(page);

      await expect(page).toHaveURL(new RegExp(`${path}$`));
      await expect(page.getByRole("heading", { name: heading })).toBeVisible();
    });
  }
});

test.describe("tenant login — error handling", () => {
  test("invalid credentials render an accessible, backend-worded alert", async ({ page }) => {
    await mockJson(
      page,
      "**/v1/auth/login",
      401,
      apiError("INVALID_CREDENTIALS", "Invalid email or password")
    );

    await page.goto("/login");
    await submitLogin(page);

    const alert = page.getByRole("alert").filter({ hasText: "Sign-in failed" });
    await expect(alert).toBeVisible();
    await expect(alert).toContainText("Invalid email or password");
    // Must stay on the login page — no redirect on failure.
    await expect(page).toHaveURL(/\/login$/);
  });

  test("suspended tenant account renders the same generic alert copy (anti-enumeration)", async ({
    page,
  }) => {
    await mockJson(
      page,
      "**/v1/auth/login",
      403,
      apiError("USER_SUSPENDED", "Invalid email or password")
    );

    await page.goto("/login");
    await submitLogin(page);

    const alert = page.getByRole("alert").filter({ hasText: "Sign-in failed" });
    await expect(alert).toBeVisible();
    await expect(alert).toContainText("Invalid email or password");
  });

  test("unresolvable/suspended tenant renders the backend's TENANT_UNAVAILABLE message", async ({
    page,
  }) => {
    await mockJson(
      page,
      "**/v1/auth/login",
      403,
      apiError("TENANT_UNAVAILABLE", "This tenant is unavailable.")
    );

    await page.goto("/login");
    await submitLogin(page);

    const alert = page.getByRole("alert").filter({ hasText: "Sign-in failed" });
    await expect(alert).toBeVisible();
    await expect(alert).toContainText("This tenant is unavailable.");
  });

  test("VALIDATION_ERROR field errors are attached to the matching form fields", async ({
    page,
  }) => {
    await mockJson(
      page,
      "**/v1/auth/login",
      400,
      apiError("VALIDATION_ERROR", "Validation failed", [
        { field: "email", message: "Enter a valid email address." },
      ])
    );

    await page.goto("/login");
    await submitLogin(page);

    await expect(page.getByText("Enter a valid email address.")).toBeVisible();
  });
});

test.describe("platform admin login", () => {
  test("posts to the platform-admin endpoint and redirects to the platform admin dashboard", async ({
    page,
  }) => {
    let tenantLoginCalled = false;
    await page.route("**/v1/auth/login", (route) => {
      tenantLoginCalled = true;
      return route.abort();
    });

    const token = fakeJwt({ role: "PLATFORM_ADMIN" });
    await mockJson(
      page,
      "**/v1/platform-admin/auth/login",
      200,
      apiSuccess(loginResponseBody(token))
    );

    await page.goto("/platform-admin/login");
    // No dashboard chrome on the login route itself.
    await expect(page.getByRole("navigation", { name: "Primary" })).toHaveCount(0);

    await submitLogin(page);

    await expect(page).toHaveURL(/\/platform-admin\/dashboard$/);
    await expect(page.getByRole("heading", { name: "Platform Admin Dashboard" })).toBeVisible();
    expect(tenantLoginCalled).toBe(false);
  });

  test("invalid platform admin credentials render an accessible alert", async ({ page }) => {
    await mockJson(
      page,
      "**/v1/platform-admin/auth/login",
      401,
      apiError("INVALID_CREDENTIALS", "Invalid email or password")
    );

    await page.goto("/platform-admin/login");
    await submitLogin(page);

    const alert = page.getByRole("alert").filter({ hasText: "Sign-in failed" });
    await expect(alert).toBeVisible();
    await expect(alert).toContainText("Invalid email or password");
    await expect(page).toHaveURL(/\/platform-admin\/login$/);
  });
});
