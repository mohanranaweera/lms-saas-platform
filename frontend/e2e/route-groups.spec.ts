import { test, expect } from "@playwright/test";

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
  test("login page renders a disabled placeholder form", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByText("Not yet implemented", { exact: false })).toBeVisible();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeDisabled();
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
