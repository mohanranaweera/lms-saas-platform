import { test, expect, type Page } from "@playwright/test";
import { apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * ADR-037 decision 9 (amended) - mock-only. On a teacher storefront host the
 * student portal's course lists are that teacher's only (the backend filters
 * them; proven by the backend tests), and the portal says so with a link to
 * the institute site. The browser resolves `*.lms.test` to this machine, as
 * in `teacher-storefronts.spec.ts`; the port follows the configured baseURL.
 */

test.use({ launchOptions: { args: ["--host-resolver-rules=MAP *.lms.test 127.0.0.1"] } });

const SITE_CONTEXT = {
  kind: "STOREFRONT",
  storefront: {
    slug: "alice",
    teacherId: "teacher-1",
    displayName: "Alice Perera",
    photoUrl: null,
    bio: null,
    host: "alice.storefront-e2e.lms.test",
  },
};

function hosts(baseURL: string | undefined) {
  const port = new URL(baseURL ?? "http://localhost:3000").port;
  const tenant = `storefront-e2e.lms.test${port ? `:${port}` : ""}`;
  return { tenant: `http://${tenant}`, store: `http://alice.${tenant}` };
}

async function mockStudentPortal(page: Page, siteContext: unknown) {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
  await mockJson(page, "**/v1/public/site-context", 200, apiSuccess(siteContext));
  await mockJson(page, "**/api/v1/course-tags", 200, apiSuccess([]));
  await mockJson(page, "**/api/v1/enrollments/my/catalog*", 200, apiPageSuccess([]));
}

test.describe("student portal on a storefront host", () => {
  test("desktop: the banner names the teacher and links to all courses on the institute site", async ({ page, baseURL }) => {
    const { tenant, store } = hosts(baseURL);
    await page.setViewportSize({ width: 1280, height: 900 });
    await mockStudentPortal(page, SITE_CONTEXT);
    await page.goto(`${store}/student/courses`);

    const banner = page.getByTestId("storefront-portal-banner");
    await expect(banner).toBeVisible({ timeout: 30_000 });
    await expect(banner).toContainText("Showing your courses from Alice Perera's page.");
    await expect(banner.getByRole("link", { name: "See all your courses" })).toHaveAttribute(
      "href",
      `${tenant}/student/courses`
    );
  });

  test("mobile: the banner stays readable at phone width", async ({ page, baseURL }) => {
    const { store } = hosts(baseURL);
    await page.setViewportSize({ width: 390, height: 844 });
    await mockStudentPortal(page, SITE_CONTEXT);
    await page.goto(`${store}/student/courses`);

    const banner = page.getByTestId("storefront-portal-banner");
    await expect(banner).toBeVisible({ timeout: 30_000 });
    await expect(banner.getByRole("link", { name: "See all your courses" })).toBeVisible();
    const box = await banner.boundingBox();
    expect(box && box.width).toBeLessThanOrEqual(390);
  });

  test("a tenant host shows no banner", async ({ page, baseURL }) => {
    const { tenant } = hosts(baseURL);
    await mockStudentPortal(page, { kind: "TENANT", storefront: null });
    const siteContext = page.waitForResponse((response) => response.url().includes("/v1/public/site-context"));
    await page.goto(`${tenant}/student/courses`);

    await siteContext;
    await expect(page.locator('header a[href="/student/courses"]').first()).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId("storefront-portal-banner")).toHaveCount(0);
  });
});
