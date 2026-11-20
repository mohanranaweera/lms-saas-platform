import { test, expect, type Page, type Route } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  fulfillJson,
  loginResponseBody,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * STORE-01 Teacher Storefronts (ADR-037) - mock-only. The browser resolves
 * `*.lms.test` to this machine (Chromium host-resolver rule), so pages are
 * served by the local Next server under a storefront-shaped host, and every
 * `/api` call is answered by `page.route`. Host resolution, scoping and the
 * server-side 308 (`src/proxy.ts`) are backend/server concerns proven by the
 * backend tests; here the tenant `storefront-e2e` does not exist, so the
 * proxy falls through and the client handles the mocked answers.
 */

test.use({ launchOptions: { args: ["--host-resolver-rules=MAP *.lms.test 127.0.0.1"] } });

const TENANT_HOST = "storefront-e2e.lms.test:3000";
const STORE_HOST = `alice.${TENANT_HOST}`;
const STORE = `http://${STORE_HOST}`;
const OLD_STORE_HOST = `old-alice.${TENANT_HOST}`;

const SITE_CONTEXT = {
  kind: "STOREFRONT",
  storefront: {
    slug: "alice",
    teacherId: "teacher-1",
    displayName: "Alice Perera",
    photoUrl: null,
    bio: "Biology teacher for A/L students.\nTen years of results.",
    host: "alice.storefront-e2e.lms.test",
  },
};

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

function course(id: string, name: string, slug: string, category: string) {
  return {
    id,
    name,
    slug,
    category,
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
}

const BIOLOGY = course("11111111-1111-1111-1111-111111111111", "A/L Biology Theory", "al-biology-theory", "Biology");
const REVISION = course("22222222-2222-2222-2222-222222222222", "Biology Revision", "biology-revision", "Revision");

/** Anonymous visitor on a storefront host: site context, tenant chrome and the scoped catalog. */
async function mockStorefront(page: Page, courses = [BIOLOGY, REVISION], paging = {}) {
  await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
  await mockJson(page, "**/v1/public/site-context", 200, apiSuccess(SITE_CONTEXT));
  await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess(PUBLIC_HOME));
  const requests: string[] = [];
  await page.route(/\/api\/v1\/public\/courses(\?.*)?$/, async (route: Route) => {
    requests.push(route.request().url());
    await fulfillJson(route, 200, apiPageSuccess(courses, paging));
  });
  return requests;
}

test.describe("storefront homepage", () => {
  test("desktop: teacher profile, published courses, search and sign-in return path", async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    const requests = await mockStorefront(page, [BIOLOGY, REVISION], { totalElements: 14, totalPages: 2, size: 12 });
    await page.goto(`${STORE}/`);

    const home = page.getByTestId("storefront-home");
    await expect(home.getByRole("heading", { level: 1, name: "Alice Perera" })).toBeVisible();
    await expect(home.getByTestId("storefront-teacher-bio")).toContainText("Biology teacher for A/L students.");
    await expect(home.getByRole("heading", { name: "Courses by Alice Perera" })).toBeVisible();
    const list = home.getByRole("list", { name: "Courses by Alice Perera" });
    await expect(list.getByRole("link")).toHaveCount(2);
    await expect(list.getByRole("link", { name: /A\/L Biology Theory/ })).toHaveAttribute("href", "/courses/al-biology-theory");
    // The tenant homepage is not rendered on a storefront host.
    await expect(page.getByTestId("branded-hero")).toHaveCount(0);

    await home.getByLabel("Search courses").fill("revision");
    await expect(list.getByRole("link")).toHaveCount(1);
    await expect(list.getByText("Biology Revision")).toBeVisible();
    await home.getByLabel("Search courses").fill("zzz");
    await expect(home.getByText("No courses match your search")).toBeVisible();
    await home.getByRole("button", { name: "Clear search" }).click();
    await expect(list.getByRole("link")).toHaveCount(2);

    await home.getByRole("button", { name: "Next" }).click();
    await expect.poll(() => requests.some((url) => new URL(url).searchParams.get("page") === "1")).toBe(true);

    // Sign-in/registration come back to this storefront page.
    await expect(page.locator('header a[href^="/login"]').first()).toHaveAttribute("href", "/login?returnTo=%2F");
    await expect(page.locator('header a[href^="/register"]').first()).toHaveAttribute("href", "/register?returnTo=%2F");
  });

  test("mobile: single-column layout with the teacher first", async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await mockStorefront(page);
    await page.goto(`${STORE}/`);

    const heading = page.getByRole("heading", { level: 1, name: "Alice Perera" });
    await expect(heading).toBeVisible();
    const cards = page.getByRole("list", { name: "Courses by Alice Perera" }).getByRole("listitem");
    await expect(cards).toHaveCount(2);
    const first = await cards.nth(0).boundingBox();
    const second = await cards.nth(1).boundingBox();
    expect(first && second).toBeTruthy();
    // Stacked: same left edge, second below the first, nothing wider than the viewport.
    expect(Math.abs(first!.x - second!.x)).toBeLessThan(2);
    expect(second!.y).toBeGreaterThan(first!.y);
    expect(first!.width).toBeLessThanOrEqual(390);
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(1);
  });

  test("an empty storefront says so instead of showing the tenant catalog", async ({ page }) => {
    await mockStorefront(page, []);
    await page.goto(`${STORE}/`);
    await expect(page.getByText("No courses here yet")).toBeVisible();
  });

  test("a tenant host keeps the tenant homepage", async ({ page }) => {
    await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session"));
    await mockJson(page, "**/v1/public/site-context", 200, apiSuccess({ kind: "TENANT", storefront: null }));
    await mockJson(page, "**/v1/public/tenant-config/home", 200, apiSuccess(PUBLIC_HOME));
    await mockJson(page, /\/api\/v1\/public\/courses(\?.*)?$/, 200, apiPageSuccess([BIOLOGY]));
    await page.goto(`http://${TENANT_HOST}/`);

    await expect(page.getByTestId("branded-hero")).toBeVisible();
    await expect(page.getByTestId("storefront-home")).toHaveCount(0);
    await expect(page.locator('header a[href^="/login"]').first()).toHaveAttribute("href", "/login");
  });
});

test.describe("unknown and retired storefront hosts", () => {
  test("STOREFRONT_NOT_FOUND shows the not-found page with a link to the tenant site", async ({ page }) => {
    await mockJson(page, "**/v1/auth/refresh", 404, apiError("STOREFRONT_NOT_FOUND", "Storefront not found"));
    await mockJson(page, "**/v1/public/**", 404, apiError("STOREFRONT_NOT_FOUND", "Storefront not found"));
    await page.goto(`http://nobody.${TENANT_HOST}/courses`);

    await expect(page.getByRole("heading", { name: "Storefront not found" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Go to the institute site" })).toHaveAttribute(
      "href",
      `http://${TENANT_HOST}/`
    );
    // Never the tenant catalog.
    await expect(page.getByRole("heading", { name: "All courses" })).toHaveCount(0);
  });

  test("STOREFRONT_MOVED moves to the same path on the current storefront host", async ({ page }) => {
    await page.route("**/api/**", async (route: Route) => {
      const url = new URL(route.request().url());
      if (url.host === OLD_STORE_HOST) {
        await fulfillJson(route, 404, {
          ...apiError("STOREFRONT_MOVED", "Storefront moved"),
          error: { code: "STOREFRONT_MOVED", message: "Storefront moved", fieldErrors: [], details: { host: "alice.storefront-e2e.lms.test" } },
        });
        return;
      }
      if (url.pathname.endsWith("/v1/auth/refresh")) return fulfillJson(route, 401, apiError("UNAUTHENTICATED", "No session"));
      if (url.pathname.endsWith("/v1/public/site-context")) return fulfillJson(route, 200, apiSuccess(SITE_CONTEXT));
      if (url.pathname.endsWith("/v1/public/tenant-config/home")) return fulfillJson(route, 200, apiSuccess(PUBLIC_HOME));
      if (/\/v1\/public\/courses$/.test(url.pathname)) return fulfillJson(route, 200, apiPageSuccess([BIOLOGY]));
      return route.continue();
    });

    await page.goto(`http://${OLD_STORE_HOST}/courses?ref=old`);
    await expect(page).toHaveURL(`${STORE}/courses?ref=old`);
    await expect(page.getByRole("heading", { name: "Storefront not found" })).toHaveCount(0);
  });
});

test.describe("sign-in return path", () => {
  test("after sign-in on a storefront the student returns to the page they came from", async ({ page }) => {
    await mockStorefront(page);
    await mockJson(page, "**/v1/public/courses/al-biology-theory", 200, apiSuccess(BIOLOGY));
    await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(fakeJwt({ role: "STUDENT" }))));
    await page.goto(`${STORE}/login?returnTo=${encodeURIComponent("/courses/al-biology-theory")}`);

    await page.getByLabel("Email").fill("student@example.test");
    await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
    await page.getByRole("button", { name: "Sign in" }).click();
    // Generous timeout: the dev server may compile the course route on first use.
    await expect(page).toHaveURL(`${STORE}/courses/al-biology-theory`, { timeout: 30_000 });
  });

  test("an off-site returnTo is ignored and the student lands on the dashboard", async ({ page }) => {
    await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(fakeJwt({ role: "STUDENT" }))));
    await page.goto(`/login?returnTo=${encodeURIComponent("//evil.test/phish")}`);

    await page.getByLabel("Email").fill("student@example.test");
    await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/student\/dashboard$/, { timeout: 30_000 });
  });
});

test.describe("checkout on a storefront", () => {
  test("a course outside the storefront shows a friendly message", async ({ page }) => {
    const COURSE = { ...BIOLOGY, id: "course-out", teacherId: "teacher-2", status: "PUBLIC", createdAt: "2026-01-01T00:00:00Z", updatedAt: "2026-01-01T00:00:00Z" };
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
    await mockJson(page, "**/v1/public/site-context", 200, apiSuccess(SITE_CONTEXT));
    await mockJson(page, "**/v1/checkout/payment-options", 200, apiSuccess({ bankSlip: true, onlinePayment: null }));
    await mockJson(page, `**/api/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
    await mockJson(
      page,
      "**/api/v1/orders",
      422,
      apiError("STOREFRONT_PRODUCT_OUT_OF_SCOPE", "Course is not offered by this storefront")
    );
    await page.goto(`${STORE}/student/checkout/${COURSE.id}`);

    await page.getByRole("button", { name: /bank|slip/i }).first().click();
    await expect(page.getByRole("alert").filter({ hasText: "isn't sold on this teacher's page" })).toBeVisible();
  });
});

test.describe("cart on a storefront", () => {
  test("the payment composer explains an out-of-scope course from the quote", async ({ page }) => {
    const ICT = { courseId: "33333333-3333-3333-3333-333333333333", title: "2026 A/L ICT", tags: [] };
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
    await mockJson(page, "**/v1/public/site-context", 200, apiSuccess(SITE_CONTEXT));
    await mockJson(page, "**/api/v1/course-tags", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/enrollments/my/catalog*", 200, apiPageSuccess([]));
    await mockJson(page, "**/api/v1/checkout/courses*", 200, apiSuccess([ICT]));
    await mockJson(
      page,
      "**/api/v1/checkout/offers*",
      200,
      apiSuccess({
        scope: "MONTHLY",
        courseId: ICT.courseId,
        currency: "LKR",
        supported: true,
        years: [2026],
        items: [
          { scope: "MONTHLY", year: 2026, month: 10, moduleId: null, lessonId: null, label: "2026 Oct", amount: "3500.00", state: "AVAILABLE" },
        ],
      })
    );
    await mockJson(
      page,
      "**/api/v1/checkout/quote",
      422,
      apiError("STOREFRONT_PRODUCT_OUT_OF_SCOPE", "Course is not offered by this storefront")
    );

    await page.goto(`${STORE}/student/courses`);
    await page.getByTestId("global-pay-button").click();
    const composer = page.getByTestId("payment-composer");
    await expect(composer).toBeVisible();
    await composer.getByTestId("composer-course-select").selectOption(ICT.courseId);
    await composer.locator('[data-testid="composer-month"][data-month="10"]').click();
    await expect(composer.getByText("This course isn't sold on this teacher's page.", { exact: false })).toBeVisible();
  });
});

// ---------------------------------------------------------------------------
// Tenant Admin: Teachers -> Storefronts
// ---------------------------------------------------------------------------

const ASSIGNABLE = [
  { courseId: "c-bio", title: "A/L Biology Theory", slug: "al-biology-theory" },
  { courseId: "c-rev", title: "Biology Revision", slug: "biology-revision" },
];

function storefront(overrides: Record<string, unknown> = {}) {
  return {
    id: null,
    teacherId: "teacher-1",
    slug: null,
    enabled: false,
    previewUrl: null,
    publishedUrl: null,
    previousSlugs: [],
    products: [],
    assignableCourses: ASSIGNABLE,
    ...overrides,
  };
}

interface AdminState {
  settingsBodies: { slug: string; enabled: boolean }[];
  productBodies: { items: { courseId: string; published: boolean; sortOrder: number }[] }[];
  current: ReturnType<typeof storefront>;
}

async function mockStorefrontAdmin(page: Page): Promise<AdminState> {
  const state: AdminState = { settingsBodies: [], productBodies: [], current: storefront() };
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
  await page.route(/\/api\/v1\/teacher-storefronts(\/.*)?(\?.*)?$/, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname.replace(/^.*\/v1\/teacher-storefronts/, "");
    if (path === "" && request.method() === "GET") {
      return fulfillJson(
        route,
        200,
        apiPageSuccess([
          { teacherId: "teacher-1", teacherName: "Alice Perera", storefront: null },
          {
            teacherId: "teacher-2",
            teacherName: "Bob Silva",
            storefront: storefront({
              id: "s-2",
              teacherId: "teacher-2",
              slug: "bob",
              enabled: true,
              publishedUrl: "http://bob.abc.lms.test/",
              products: [{ courseId: "c-x", courseTitle: "Chemistry", courseSlug: "chem", published: true, sortOrder: 0 }],
            }),
          },
        ])
      );
    }
    if (path === "/teacher-1" && request.method() === "GET") {
      // Like the backend: 404 until the first PUT creates the storefront.
      return state.current.id
        ? fulfillJson(route, 200, apiSuccess(state.current))
        : fulfillJson(route, 404, apiError("NOT_FOUND", "Storefront not found"));
    }
    if (path === "/teacher-1" && request.method() === "PUT") {
      const body = request.postDataJSON() as { slug: string; enabled: boolean };
      state.settingsBodies.push(body);
      if (body.slug === "taken-slug") {
        return fulfillJson(route, 409, apiError("STOREFRONT_SLUG_TAKEN", "Slug taken"));
      }
      const url = `http://${body.slug}.abc.lms.test/`;
      state.current = storefront({
        ...state.current,
        id: "s-1",
        slug: body.slug,
        enabled: body.enabled,
        previewUrl: url,
        publishedUrl: body.enabled ? url : null,
      });
      return fulfillJson(route, 200, apiSuccess(state.current));
    }
    if (path === "/teacher-1/products" && request.method() === "PUT") {
      const body = request.postDataJSON() as AdminState["productBodies"][number];
      state.productBodies.push(body);
      state.current = storefront({
        ...state.current,
        products: body.items.map((item) => ({
          ...item,
          courseTitle: ASSIGNABLE.find((course) => course.courseId === item.courseId)?.title ?? "",
          courseSlug: "",
        })),
      });
      return fulfillJson(route, 200, apiSuccess(state.current));
    }
    return fulfillJson(route, 404, apiError("NOT_FOUND", "Not found"));
  });
  return state;
}

test.describe("tenant admin storefront management", () => {
  test("list shows each teacher's storefront status", async ({ page }) => {
    await mockStorefrontAdmin(page);
    await page.goto("/tenant-admin/teachers/storefronts");

    await expect(page.getByRole("heading", { name: "Teacher storefronts", level: 1 })).toBeVisible();
    await expect(page.getByRole("link", { name: "Teacher storefronts" }).first()).toBeVisible();
    const table = page.getByRole("table", { name: "Teacher storefronts" });
    const alice = table.getByRole("row", { name: /Alice Perera/ });
    await expect(alice.getByText("Not set up")).toBeVisible();
    const bob = table.getByRole("row", { name: /Bob Silva/ });
    await expect(bob.getByText("Live")).toBeVisible();
    await expect(bob.getByText("1 of 1 published")).toBeVisible();
    await alice.getByRole("link", { name: /Set up/ }).click();
    await expect(page).toHaveURL(/\/tenant-admin\/teachers\/storefronts\/teacher-1$/);
    await expect(page.getByRole("heading", { name: "Alice Perera's storefront" })).toBeVisible();
  });

  test("edit: off by default, slug errors from client and server, save, then publish courses", async ({ page }) => {
    const state = await mockStorefrontAdmin(page);
    await page.goto("/tenant-admin/teachers/storefronts/teacher-1");

    const settings = page.getByTestId("storefront-settings-form");
    await expect(page.getByTestId("storefront-not-set-up")).toBeVisible();
    const toggle = settings.getByRole("switch", { name: "Enable personal homepage" });
    await expect(toggle).toHaveAttribute("aria-checked", "false");
    await expect(page.getByTestId("storefront-products-locked")).toBeVisible();

    // Client hint (reserved) blocks the request.
    const slug = settings.getByLabel("Address");
    await slug.fill("admin");
    await settings.getByRole("button", { name: "Save storefront" }).click();
    await expect(settings.getByRole("alert")).toContainText("reserved");
    expect(state.settingsBodies).toHaveLength(0);

    // The server is the authority: 409 STOREFRONT_SLUG_TAKEN lands on the field.
    await slug.fill("taken-slug");
    await settings.getByRole("button", { name: "Save storefront" }).click();
    await expect(settings.getByRole("alert")).toContainText("already used by another teacher");
    await expect(slug).toHaveAttribute("aria-invalid", "true");

    // Keyboard toggle + save.
    await slug.fill("Alice-P");
    await toggle.focus();
    await page.keyboard.press("Space");
    await expect(toggle).toHaveAttribute("aria-checked", "true");
    await settings.getByRole("button", { name: "Save storefront" }).click();
    await expect(page.getByTestId("storefront-settings-saved")).toBeVisible();
    expect(state.settingsBodies.at(-1)).toEqual({ slug: "alice-p", enabled: true });
    await expect(page.getByTestId("storefront-published-url")).toContainText("http://alice-p.abc.lms.test/");
    await expect(page.getByTestId("storefront-status").first()).toContainText("Live");

    // Product picker: list Biology Revision, publish it, order 2.
    const products = page.getByTestId("storefront-products-form");
    await products.getByLabel(/Biology Revision - list on this storefront/).check();
    await products.getByRole("switch", { name: "Published: Biology Revision" }).click();
    await products.getByRole("spinbutton", { name: "Sort order: Biology Revision" }).fill("2");
    await products.getByRole("button", { name: "Save courses" }).click();
    await expect(page.getByTestId("storefront-products-saved")).toBeVisible();
    expect(state.productBodies.at(-1)).toEqual({ items: [{ courseId: "c-rev", published: true, sortOrder: 2 }] });
  });

  test("a role without the capability gets the permission-denied state", async ({ page }) => {
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
    await mockJson(page, /\/api\/v1\/teacher-storefronts(\?.*)?$/, 403, apiError("FORBIDDEN", "Not allowed"));
    await page.goto("/tenant-admin/teachers/storefronts");
    await expect(page.getByRole("alert").filter({ hasText: "You don't have permission to view this." })).toBeVisible();
    await expect(page.getByRole("table", { name: "Teacher storefronts" })).toHaveCount(0);
  });
});

test.describe("teacher portal storefront status", () => {
  test("shows the live address read-only", async ({ page }) => {
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TEACHER" }))));
    await mockJson(page, "**/v1/teachers/me", 404, apiError("NOT_FOUND", "No profile"));
    await mockJson(
      page,
      "**/v1/teacher-storefronts/me",
      200,
      apiSuccess({
        ...storefront({ id: "s-1", slug: "alice", enabled: true, publishedUrl: "http://alice.abc.lms.test/" }),
        assignableCourses: undefined,
        products: [{ courseId: "c-bio", courseTitle: "A/L Biology Theory", courseSlug: "x", published: true, sortOrder: 0 }],
      })
    );
    await page.goto("/teacher/profile");
    const section = page.getByTestId("teacher-storefront-status");
    await expect(section.getByText("Live")).toBeVisible();
    await expect(section.getByTestId("teacher-storefront-url")).toHaveAttribute("href", "http://alice.abc.lms.test/");
    await expect(section.getByRole("button")).toHaveCount(0);
  });

  test("404 means not set up: ask the institute admin", async ({ page }) => {
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TEACHER" }))));
    await mockJson(page, "**/v1/teachers/me", 404, apiError("NOT_FOUND", "No profile"));
    await mockJson(page, "**/v1/teacher-storefronts/me", 404, apiError("NOT_FOUND", "No storefront"));
    await page.goto("/teacher/profile");
    await expect(page.getByTestId("teacher-storefront-none")).toContainText("Ask your institute admin");
  });
});
