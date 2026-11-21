import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson } from "./fixtures/auth-mocks";

/**
 * ADR-037 decision 6 (amended 2026-11-21) - mock-only. On the institute
 * (tenant) host, a course whose teachers all sell on their own storefront
 * answers `404 COURSE_ON_STOREFRONT` with `details.host`; the page replaces
 * the URL with the same path on that storefront, but only when the host is a
 * storefront of this tenant. Hiding itself is proven by the backend tests.
 * The browser resolves `*.lms.test` to this machine, as in
 * `teacher-storefronts.spec.ts`; the port follows the configured baseURL.
 */

test.use({ launchOptions: { args: ["--host-resolver-rules=MAP *.lms.test 127.0.0.1"] } });

function hosts(baseURL: string | undefined) {
  const port = new URL(baseURL ?? "http://localhost:3000").port;
  const tenant = `storefront-e2e.lms.test${port ? `:${port}` : ""}`;
  return { tenantHost: tenant, storeHost: `alice.${tenant}`, tenant: `http://${tenant}`, store: `http://alice.${tenant}` };
}

const PUBLIC_HOME = {
  publicPageEnabled: true,
  instituteName: "ABC Institute",
  logoUrl: null,
  faviconUrl: null,
  homepageUrl: null,
  subHeroEnabled: false,
  ctaEnabled: false,
  supportVideoUrl: null,
  desktopBannerUrls: [],
  mobileBannerUrls: [],
  featuredCourses: [],
  theme: null,
};

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

const BIOLOGY = {
  id: "11111111-1111-1111-1111-111111111111",
  name: "A/L Biology Theory",
  slug: "al-biology-theory",
  category: "Biology",
  subject: null,
  stream: null,
  grade: null,
  academicYear: null,
  description: null,
  price: 3500,
  accessDurationDays: null,
  enrollmentRule: null,
  pricingModel: "ONE_TIME",
  resolvedAmount: 3500,
  currency: "LKR",
  requiresManualQuote: false,
  teacherName: "Alice Perera",
  coverImageUrl: null,
  registrationEnabled: true,
  outline: { modules: [] },
  tags: [],
};

function courseOnStorefront(host: unknown) {
  const base = apiError("COURSE_ON_STOREFRONT", "Course not found");
  return { ...base, error: { ...base.error, details: { host } } };
}

/** Tenant host hides the course (pointing to `targetHost`); the storefront host serves it. */
async function mockSites(page: Page, tenantHost: string, targetHost: unknown) {
  const detailHosts: string[] = [];
  await page.route("**/api/**", async (route: Route) => {
    const url = new URL(route.request().url());
    const onTenant = url.host === tenantHost;
    if (url.pathname.endsWith("/v1/auth/refresh")) return fulfillJson(route, 401, apiError("UNAUTHENTICATED", "No session"));
    if (url.pathname.endsWith("/v1/public/site-context")) {
      return fulfillJson(route, 200, apiSuccess(onTenant ? { kind: "TENANT", storefront: null } : SITE_CONTEXT));
    }
    if (url.pathname.endsWith("/v1/public/tenant-config/home")) return fulfillJson(route, 200, apiSuccess(PUBLIC_HOME));
    if (url.pathname.endsWith(`/v1/public/courses/${BIOLOGY.slug}`)) {
      detailHosts.push(url.host);
      return onTenant ? fulfillJson(route, 404, courseOnStorefront(targetHost)) : fulfillJson(route, 200, apiSuccess(BIOLOGY));
    }
    if (/\/v1\/public\/courses$/.test(url.pathname)) return fulfillJson(route, 200, apiPageSuccess([]));
    return fulfillJson(route, 200, apiSuccess(null));
  });
  return detailHosts;
}

test.describe("institute host: course sold on a teacher storefront", () => {
  test("redirects (replace) to the same path on that storefront", async ({ page, baseURL }) => {
    const { tenantHost, storeHost, tenant, store } = hosts(baseURL);
    const detailHosts = await mockSites(page, tenantHost, "alice.storefront-e2e.lms.test");
    await page.goto(`${tenant}/`);
    await page.goto(`${tenant}/courses/${BIOLOGY.slug}?ref=home`);

    await expect(page).toHaveURL(`${store}/courses/${BIOLOGY.slug}?ref=home`);
    await expect(page.getByRole("heading", { name: BIOLOGY.name })).toBeVisible();
    expect(detailHosts).toContain(tenantHost);
    expect(detailHosts).toContain(storeHost);
    // Replaced, not pushed: Back returns to the page before the hidden course.
    await page.goBack();
    await expect(page).toHaveURL(`${tenant}/`);
  });

  test("a host outside this tenant is never followed: the page shows course not found", async ({ page, baseURL }) => {
    const { tenantHost, tenant } = hosts(baseURL);
    await mockSites(page, tenantHost, "alice.other-tenant.lms.test");
    await page.goto(`${tenant}/courses/${BIOLOGY.slug}`);

    await expect(page.getByText("Course not found", { exact: true })).toBeVisible();
    await expect(page).toHaveURL(`${tenant}/courses/${BIOLOGY.slug}`);
    await expect(page.getByRole("heading", { name: "Storefront not found" })).toHaveCount(0);
  });
});
