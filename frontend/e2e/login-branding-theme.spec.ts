import { test, expect, type Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, mockJson, mockStudentRegistrationPolicy } from "./fixtures/auth-mocks";

/**
 * LOGIN-01 Phase A: tenant auth pages show the host institute's logo (never
 * "LMS Platform"), Platform Admin keeps platform branding, and the light/dark
 * switch persists per browser with no server involvement. Every backend call
 * is mocked; all institute data is invented.
 */

const LOGO = "https://assets.test/northwind-logo.svg";

const HOME = {
  publicPageEnabled: true,
  instituteName: "Northwind Academy",
  logoUrl: LOGO as string | null,
  faviconUrl: null,
  homepageUrl: null,
  subHeroEnabled: false,
  ctaEnabled: false,
  supportVideoUrl: null,
  desktopBannerUrls: [],
  mobileBannerUrls: [],
  featuredCourses: [],
  theme: {
    primaryColor: "#15803D",
    accentColor: "#CA8A04",
    backgroundColor: "#F7FBF8",
    surfaceColor: "#FFFFFF",
    textColor: "#14281D",
    headingFont: "INTER",
    bodyFont: "INTER",
  },
  logoDisplaySize: "STANDARD",
  teacherAutoRotate: false,
  supportEmail: null,
  supportPhone: null,
  institute: null,
};

async function mockAnonymous(page: Page, home: Record<string, unknown> | null = HOME) {
  await page.route("https://assets.test/**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="180"><rect width="600" height="180" fill="#0B3D2E"/></svg>',
    })
  );
  await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/platform-admin/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  if (home) {
    await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess(home));
  } else {
    await mockJson(page, "**/v1/public/tenant-config/home", 404, apiError("NOT_FOUND", "Not found"));
  }
  await mockStudentRegistrationPolicy(page);
  await mockJson(page, "**/v1/public/teachers*", 200, apiPageSuccess([]));
  await mockJson(page, "**/v1/public/courses*", 200, apiPageSuccess([]));
}

test.describe("institute logo on auth pages", () => {
  test("login shows the institute logo and name, never platform text", async ({ page }) => {
    await mockAnonymous(page);
    await page.goto("/login");

    const brand = page.getByTestId("auth-brand");
    await expect(brand).toHaveAttribute("href", "/");
    const logo = brand.getByRole("img", { name: "Northwind Academy" });
    await expect(logo).toHaveAttribute("src", LOGO);
    await expect(logo).toBeVisible();
    expect((await logo.boundingBox())?.width ?? 0).toBeGreaterThan(40);
    await expect(page.getByText("Access your Northwind Academy account.")).toBeVisible();
    await expect(page.getByText("LMS Platform")).toHaveCount(0);
  });

  test("register shows the same institute logo", async ({ page }) => {
    await mockAnonymous(page);
    await page.goto("/register");

    await expect(page.getByTestId("auth-brand").getByRole("img", { name: "Northwind Academy" })).toBeVisible();
    await expect(page.getByText("LMS Platform")).toHaveCount(0);
  });

  test("without a logo the institute name is shown", async ({ page }) => {
    await mockAnonymous(page, { ...HOME, logoUrl: null });
    await page.goto("/login");

    await expect(page.getByTestId("auth-brand")).toBeVisible();
    await expect(page.getByTestId("auth-brand")).toContainText("Northwind Academy");
    await expect(page.getByTestId("auth-brand").getByRole("img")).toHaveCount(0);
  });

  test("an unresolved institute falls back to neutral platform text", async ({ page }) => {
    await mockAnonymous(page, null);
    await page.goto("/login");

    await expect(page.getByTestId("auth-brand-fallback")).toHaveText("LMS Platform");
    await expect(page.getByText("Access your account.")).toBeVisible();
  });

  test("the login button uses the institute's brand colour", async ({ page }) => {
    await mockAnonymous(page);
    await page.goto("/login");
    // The theme arrives with the institute read; wait for it rather than sampling the default.
    await expect(page.getByTestId("auth-brand")).toBeVisible();

    await expect
      .poll(() => page.getByRole("button", { name: "Sign in" }).evaluate((element) => getComputedStyle(element).backgroundColor))
      .toBe("rgb(21, 128, 61)");
  });

  test("Platform Admin login keeps platform branding", async ({ page }) => {
    await mockAnonymous(page);
    await page.goto("/platform-admin/login");

    await expect(page.getByText("LMS Platform — Platform Admin")).toBeVisible();
    await expect(page.getByTestId("auth-brand")).toHaveCount(0);
  });
});

test.describe("light/dark switch", () => {
  test("switching to dark persists across reloads and pages, and back to light", async ({ page }) => {
    await mockAnonymous(page);
    await page.goto("/login");
    const html = page.locator("html");
    const toggle = page.getByRole("button", { name: "Dark theme" });
    await expect(html).not.toHaveClass(/\bdark\b/);
    await expect(toggle).toHaveAttribute("aria-pressed", "false");

    await toggle.click();
    await expect(html).toHaveClass(/\bdark\b/);
    await expect(toggle).toHaveAttribute("aria-pressed", "true");

    await page.reload();
    await expect(html).toHaveClass(/\bdark\b/);
    await page.goto("/");
    await expect(html).toHaveClass(/\bdark\b/);
    const publicToggle = page.getByTestId("public-site-header").getByRole("button", { name: "Dark theme" });
    await expect(publicToggle).toHaveAttribute("aria-pressed", "true");

    await publicToggle.click();
    await expect(html).not.toHaveClass(/\bdark\b/);
  });

  test("follows the OS preference until the user chooses", async ({ page }) => {
    await page.emulateMedia({ colorScheme: "dark" });
    await mockAnonymous(page);
    await page.goto("/login");

    await expect(page.locator("html")).toHaveClass(/\bdark\b/);
  });

  test("dark mode keeps the tenant brand colour but not its light background", async ({ page }) => {
    await page.emulateMedia({ colorScheme: "dark" });
    await mockAnonymous(page);
    await page.goto("/");

    const vars = await page
      .locator(".tenant-public-theme")
      .first()
      .evaluate((element) => {
        const style = getComputedStyle(element);
        return {
          background: style.getPropertyValue("--background").trim(),
          primary: style.getPropertyValue("--primary").trim(),
        };
      });
    expect(vars.background).toBe("#0B1120");
    expect(vars.background).not.toBe("#F7FBF8");
    expect(vars.primary).not.toBe("");
  });

  test("the switch renders without hydration errors", async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error" && /hydrat/i.test(message.text())) errors.push(message.text());
    });
    await page.addInitScript(() => localStorage.setItem("lms-theme", "dark"));
    await mockAnonymous(page);
    await page.goto("/login");

    await expect(page.getByRole("button", { name: "Dark theme" })).toHaveAttribute("aria-pressed", "true");
    expect(errors).toEqual([]);
  });
});
