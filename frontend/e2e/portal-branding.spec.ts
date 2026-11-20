import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Student and Teacher portals carry the tenant's logo, favicon, tab title and
 * contact footer (from the host-scoped public home projection), and the
 * portal header never scrolls sideways on phones.
 */
const LOGO = "https://assets.test/portal-logo.svg";
const FAVICON = "https://assets.test/portal-favicon.svg";

const HOME = {
  publicPageEnabled: true,
  instituteName: "Northwind Academy",
  logoUrl: LOGO,
  faviconUrl: FAVICON,
  homepageUrl: null,
  subHeroEnabled: true,
  ctaEnabled: true,
  supportVideoUrl: null,
  desktopBannerUrls: [],
  mobileBannerUrls: [],
  featuredCourses: [],
  theme: { primaryColor: null, accentColor: null, backgroundColor: null, surfaceColor: null, textColor: null, headingFont: "INTER", bodyFont: "INTER" },
  logoDisplaySize: "STANDARD",
  teacherAutoRotate: false,
  supportEmail: "help@northwind.example",
  supportPhone: "+94 11 000 0000",
};

async function setup(page: Page, role: "STUDENT" | "TEACHER", home: Record<string, unknown> = HOME) {
  // Everything the dashboards load besides branding fails with a plain API error (widgets show their own
  // error states): this spec is about the shell.
  await page.route("**/api/v1/**", (route) =>
    route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify(apiError("UNAVAILABLE", "Unavailable in this test")) })
  );
  await page.route("https://assets.test/**", (route) =>
    route.fulfill({
      status: 200,
      contentType: "image/svg+xml",
      body: '<svg xmlns="http://www.w3.org/2000/svg" width="600" height="180"><rect width="600" height="180" fill="#0e7490"/></svg>',
    })
  );
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role }))));
  await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess(home));
}

for (const [role, path, label] of [
  ["STUDENT", "/student/dashboard", "Student Portal"],
  ["TEACHER", "/teacher/dashboard", "Teacher Portal"],
] as const) {
  test.describe(`${label} branding`, () => {
    test("shows the tenant logo, favicon, tab title and contact footer", async ({ page }) => {
      await setup(page, role);
      await page.setViewportSize({ width: 1440, height: 900 });
      await page.goto(path);
      const brand = page.getByTestId("portal-brand").first();
      await expect(brand.getByRole("img", { name: "Northwind Academy" })).toBeVisible();
      await expect(brand).toContainText(label);
      await expect(page).toHaveTitle(`${label} | Northwind Academy`);
      await expect(page.locator("link[data-tenant-favicon]")).toHaveAttribute("href", FAVICON);
      const footer = page.getByTestId("portal-footer");
      await expect(footer).toContainText(`© ${new Date().getFullYear()} Northwind Academy. All rights reserved.`);
      await expect(footer.getByRole("link", { name: "help@northwind.example" })).toHaveAttribute("href", "mailto:help@northwind.example");
    });

    test("mobile header shows the compact logo without horizontal overflow", async ({ page }) => {
      await setup(page, role);
      await page.setViewportSize({ width: 320, height: 700 });
      await page.goto(path);
      await expect(page.getByTestId("portal-brand-compact").getByRole("img", { name: "Northwind Academy" })).toBeVisible();
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBe(0);
    });
  });
}

test("without a resolvable institute the portal keeps its neutral label and no footer", async ({ page }) => {
  await setup(page, "STUDENT");
  await mockJson(page, "**/v1/public/tenant-config/home", 404, { success: false, data: null, error: { code: "NOT_FOUND", message: "Not found" } });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/student/dashboard");
  await expect(page.getByRole("complementary").getByText("Student Portal", { exact: true })).toBeVisible();
  await expect(page.getByTestId("portal-footer")).toHaveCount(0);
  await expect(page.locator("link[data-tenant-favicon]")).toHaveCount(0);
});
