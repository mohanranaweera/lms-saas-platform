import { test, expect } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  mockJson,
  mockStudentRegistrationPolicy,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

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

  test("register page renders a real, enabled student self-registration form", async ({
    page,
  }) => {
    // Wave 3 (PAR-03-01) replaced the disabled placeholder with a real form —
    // see `student-registration.spec.ts` for its full submit/error/policy
    // coverage; this route-group smoke test only confirms the fields are
    // genuinely interactive (not the disabled-fieldset shell this route used
    // to render).
    await mockStudentRegistrationPolicy(page);
    await page.goto("/register");
    await expect(page.getByLabel("Full name")).toBeEnabled();
    await expect(page.getByLabel("Email")).toBeEnabled();
    await expect(page.getByRole("button", { name: "Create account" })).toBeEnabled();
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
  const dashboards: Array<{ path: string; heading: string; placeholder: boolean }> = [
    // `/student/dashboard`'s heading is "Overview" as of MVP-013 (Student
    // Dashboard, SDASH-1); `/teacher/dashboard`'s heading is "Overview" as of
    // MVP-014 (Teacher Dashboard, TDASH-1); `/tenant-admin/dashboard`'s
    // heading is "Overview" as of MVP-015 (Tenant Admin Dashboard,
    // TADASH-1) — no longer the original static "Tenant Admin Dashboard"
    // placeholder. Platform Admin remains that original static "<Role>
    // Dashboard" placeholder this test was written for.
    { path: "/student/dashboard", heading: "Overview", placeholder: false },
    { path: "/teacher/dashboard", heading: "Overview", placeholder: false },
    { path: "/tenant-admin/dashboard", heading: "Overview", placeholder: false },
    { path: "/platform-admin/dashboard", heading: "Platform Admin Dashboard", placeholder: true },
  ];

  test.beforeEach(async ({ page }) => {
    // `/student/dashboard`, `/teacher/dashboard`, and `/tenant-admin/dashboard`
    // are guarded by `RouteGuard` (MVP-006 Student Management, MVP-014
    // Teacher Dashboard, MVP-015 Tenant Admin Dashboard) — mock a successful
    // silent refresh so the guard resolves for those three.
    // `/platform-admin/dashboard` is now also `RouteGuard`-wrapped (MVP-020) —
    // its own refresh mock is set up per-test below (different `kind`/token
    // shape than the other three), not here.
    const token = fakeJwt({ role: "STUDENT" });
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
    // `/student/dashboard`, `/teacher/dashboard`, and `/tenant-admin/dashboard`
    // are real data-driven pages — mock their reads so they render
    // deterministically; `/platform-admin/dashboard` is still a static
    // placeholder and ignores these mocks.
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));
    await mockJson(page, "**/v1/courses*", 200, apiSuccess({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }));
    // `/tenant-admin/dashboard`'s own two new reads (MVP-015 TADASH-1):
    // `GET /v1/students` and `GET /api/v1/ledger/dashboard*`. Its course-count
    // reads (`GET /api/v1/courses...`) are already covered by the
    // `**/v1/courses*` mock above.
    await mockJson(page, "**/v1/students", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/dashboard*", 200, apiPageSuccess([]));
  });

  for (const { path, heading, placeholder } of dashboards) {
    test(`${path} renders its portal shell${placeholder ? " and placeholder dashboard" : ""}`, async ({
      page,
    }) => {
      // `(tenant-admin)` and `(platform-admin)` are each wrapped in their own
      // `RouteGuard` (MVP-007; MVP-020 for platform-admin), which calls
      // ensureAccessToken(kind) on mount — mock a successful refresh so the
      // guard resolves instead of redirecting to the role's own login route.
      if (path.startsWith("/tenant-admin")) {
        const token = fakeJwt({ role: "TENANT_ADMIN" });
        await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
      }
      if (path.startsWith("/platform-admin")) {
        const token = fakeJwt({ role: "PLATFORM_ADMIN" });
        await mockJson(
          page,
          "**/v1/platform-admin/auth/refresh",
          200,
          apiSuccess(refreshResponseBody(token))
        );
      }
      await page.goto(path);
      await expect(page.getByRole("heading", { name: heading })).toBeVisible();
      await expect(page.getByRole("navigation", { name: "Primary" })).toBeVisible();
      await expect(page.getByRole("link", { name: "Dashboard" })).toBeVisible();
    });
  }

  test("platform admin nav links to the live tenant list (MVP-020)", async ({ page }) => {
    const token = fakeJwt({ role: "PLATFORM_ADMIN" });
    await mockJson(
      page,
      "**/v1/platform-admin/auth/refresh",
      200,
      apiSuccess(refreshResponseBody(token))
    );
    await mockJson(page, "**/v1/platform-admin/tenants*", 200, apiPageSuccess([]));

    await page.goto("/platform-admin/dashboard");
    // Scoped to the nav landmark: the dashboard placeholder's own body also
    // links to `/platform-admin/tenants` ("Go to tenants") — an unscoped
    // `getByRole("link", { name: "Tenants" })` substring-matches both.
    const nav = page.getByRole("navigation", { name: "Primary" });
    const link = nav.getByRole("link", { name: "Tenants" });
    await expect(link).toBeVisible();
    await link.click();
    await expect(page).toHaveURL(/\/platform-admin\/tenants$/);
    await expect(page.getByRole("heading", { name: "Tenants" })).toBeVisible();
  });
});

test.describe("platform admin portal — RouteGuard", () => {
  // Mirrors `teacher-dashboard.spec.ts`'s and `tenant-admin-dashboard.spec.ts`'s
  // "an unauthenticated visit ... redirects to /login before portal chrome
  // renders" pattern (MVP-020 plan §18's frontend route-guard check). Plan
  // wording says "sees a permission-denied state" but `RouteGuard`'s actual,
  // confirmed behavior (route-guard.tsx) for every portal — Platform Admin
  // included — is a client-side redirect to `<loginPath>?reason=session_expired`
  // when `ensureAccessToken` rejects, not a rendered permission-denied
  // component; this test asserts that real behavior rather than the plan's
  // literal (and here, inaccurate) wording.
  test("an unauthenticated visit to /platform-admin/tenants redirects to /platform-admin/login before portal chrome renders", async ({
    page,
  }) => {
    // No session mocked at all: `POST /v1/platform-admin/auth/refresh` fails,
    // so `RouteGuard kind="platform-admin"` redirects instead of resolving
    // `ready`.
    await mockJson(
      page,
      "**/v1/platform-admin/auth/refresh",
      401,
      apiError("UNAUTHENTICATED", "No session.")
    );

    await page.goto("/platform-admin/tenants");

    await expect(page).toHaveURL(/\/platform-admin\/login(\?|$)/);
    await expect(page).toHaveURL(/reason=session_expired/);
    // Lands on the real, chrome-less login form (`route-groups.spec.ts`'s own
    // "platform-admin login route" describe block above establishes this is
    // the login page's actual content) — no `DashboardShell` dashboard chrome
    // (primary nav landmark, mobile nav trigger) ever renders on the way to
    // the redirect. Deliberately not asserting on the text "Platform Admin"
    // itself: the login page's own heading ("Platform Admin sign in") and
    // header label legitimately contain that text too, so it can't
    // distinguish chrome from the login page.
    await expect(page.getByRole("button", { name: "Sign in" })).toBeEnabled();
    await expect(page.getByRole("navigation", { name: "Primary" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Open navigation menu" })).toHaveCount(0);
  });

  test("a Tenant Admin session (wrong portal kind) visiting /platform-admin/dashboard also redirects to /platform-admin/login", async ({
    page,
  }) => {
    // A session exists, but for the wrong `kind` — `ensureAccessToken("platform-admin")`
    // still attempts its own refresh (session state is keyed per-`kind`, see
    // auth-context.tsx) against the platform-admin refresh endpoint, which
    // this Tenant Admin session was never issued against, so it fails the
    // same way an entirely unauthenticated visit does.
    await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
    await mockJson(
      page,
      "**/v1/platform-admin/auth/refresh",
      401,
      apiError("UNAUTHENTICATED", "No session.")
    );

    await page.goto("/platform-admin/dashboard");

    await expect(page).toHaveURL(/\/platform-admin\/login(\?|$)/);
    await expect(page.getByRole("heading", { name: "Platform Admin Dashboard" })).toHaveCount(0);
  });
});

test.describe("not-found", () => {
  test("unknown route renders the not-found page", async ({ page }) => {
    await page.goto("/this-route-does-not-exist");
    await expect(page.getByRole("heading", { name: "Page not found" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to home" })).toBeVisible();
  });
});
