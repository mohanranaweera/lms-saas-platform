import { test, expect } from "@playwright/test";

test.describe("public route group", () => {
  test("home page renders marketing content and auth entry points", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await expect(page.getByRole("link", { name: "Sign in" }).first()).toBeVisible();
    await expect(page.getByRole("link", { name: "Get started" }).first()).toBeVisible();
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
    { path: "/student/dashboard", heading: "Student Dashboard" },
    { path: "/teacher/dashboard", heading: "Teacher Dashboard" },
    { path: "/tenant-admin/dashboard", heading: "Tenant Admin Dashboard" },
    { path: "/platform-admin/dashboard", heading: "Platform Admin Dashboard" },
  ];

  for (const { path, heading } of dashboards) {
    test(`${path} renders its portal shell and placeholder dashboard`, async ({
      page,
    }) => {
      await page.goto(path);
      await expect(page.getByRole("heading", { name: heading })).toBeVisible();
      await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Dashboard" })).toBeVisible();
    });
  }
});

test.describe("not-found", () => {
  test("unknown route renders the not-found page", async ({ page }) => {
    await page.goto("/this-route-does-not-exist");
    await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to home" })).toBeVisible();
  });
});
