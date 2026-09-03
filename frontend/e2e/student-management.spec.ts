import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  loginResponseBody,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Student Management (MVP-006) — Tenant Admin Student List and Student
 * Detail/Edit screens (`/tenant-admin/students`, `/tenant-admin/students/[id]`).
 *
 * No backend runs in this environment (same posture as every other spec in
 * this suite) — every `/v1/students/**` call is intercepted via
 * `page.route()`. Sessions are established via a real (mocked) login rather
 * than direct navigation, since `/tenant-admin/**` is now behind `RouteGuard`
 * (see `route-groups.spec.ts`) and the student list's "Add student"
 * visibility depends on the session's decoded role.
 *
 * `test.slow()` (triples the per-test timeout) plus generous explicit
 * `{ timeout }` values on the navigation assertions below give real headroom
 * for Turbopack's one-time dev-server compile of this file's brand-new
 * routes (`/tenant-admin/students`, its `[studentId]` child).
 *
 * That said: this file's actual "flaky under the default parallel worker
 * count" reports traced to three separate, fully deterministic causes —
 * verified directly by reproducing them in isolation with `--workers=1` on a
 * warm server/cache, where they failed exactly as reliably as under default
 * parallel workers, disproving a pure compile-contention explanation:
 *   1. `app/(auth)/login/page.tsx`'s `DASHBOARD_PATH_BY_ROLE` was missing
 *      every staff sub-role (including this module's own `STUDENT_SUPPORT`/
 *      `READ_ONLY_AUDITOR`) — fixed directly in that file (all seven staff
 *      sub-roles named in `.claude/rules/ui-ux.md` §1 now resolve to
 *      `/tenant-admin/dashboard`, same as `TENANT_ADMIN`). See
 *      "staff sub-role login" below for the coverage of the fixed behavior.
 *   2. `QueryProvider`'s default `retry: 1` means a query that fails once and
 *      then succeeds resolves via React Query's own silent automatic retry,
 *      without ever exposing a persistent, clickable "Try again" — a test
 *      simulating "the backend fails once" needs to fail at least twice
 *      (covering both the initial attempt and the one automatic retry)
 *      before the manually-triggered retry succeeds.
 *   3. `goToStudents()` depends on the sidebar nav link, which `DashboardShell`
 *      only renders inside a closed-by-default mobile `Sheet` drawer below
 *      `md` — not usable from a mobile viewport without opening the drawer
 *      first.
 * None of these are timing races, so none are "fixed" by raising a timeout.
 */

test.beforeEach(() => {
  test.slow();
});

const NAV_TIMEOUT = 20_000;

function envelope(data: unknown) {
  return {
    success: true,
    data,
    error: null,
    timestamp: new Date().toISOString(),
    traceId: "test-trace-id",
  };
}

const STUDENTS_ENDPOINT = "**/v1/students";

const ADA = {
  id: "11111111-1111-1111-1111-111111111111",
  name: "Ada Lovelace",
  email: "ada@example-institute.test",
  roleCode: "STUDENT",
  status: "ACTIVE",
};

const GRACE = {
  id: "22222222-2222-2222-2222-222222222222",
  name: "Grace Hopper",
  email: "grace@example-institute.test",
  roleCode: "STUDENT",
  status: "SUSPENDED",
};

async function loginAs(page: Page, role: string) {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(token)));
  // Also mock a successful silent refresh: `RouteGuard` (behind every
  // `/tenant-admin/**` route) calls this whenever a full navigation drops the
  // in-memory session (e.g. a test that `page.goto`s straight to a nested
  // route rather than following a client-side link click).
  await mockJson(
    page,
    "**/v1/auth/refresh",
    200,
    apiSuccess(refreshResponseBody(fakeJwt({ role })))
  );
  // This flow always lands on `/tenant-admin/dashboard` first (per
  // `DASHBOARD_PATH_BY_ROLE`) before any test navigates on to Students. As of
  // MVP-015 TADASH-1, that dashboard fires its own `GET /api/v1/courses*`
  // (always) and, for a role holding `PAYMENTS_SLIPS`/`VIEW`, `GET
  // /api/v1/ledger/dashboard*` reads — mock both here (zero/empty responses)
  // so every `loginAs` call site in this file is covered without an
  // unmocked, transient real network call during that landing, rather than
  // fixing this per call site. Registered before `STUDENTS_ENDPOINT`
  // (`**/v1/students`) is ever mocked by an individual test, and matches a
  // disjoint URL shape from it, so this does not affect any test's own
  // `STUDENTS_ENDPOINT`-scoped `requestCount`/`listCallCount` assertions.
  await mockJson(page, "**/api/v1/courses*", 200, apiPageSuccess([]));
  await mockJson(page, "**/api/v1/ledger/dashboard*", 200, apiPageSuccess([]));
  await page.goto("/login");
  await page.getByLabel("Email").fill("staff@example-institute.test");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/tenant-admin\/dashboard$/, { timeout: NAV_TIMEOUT });
}

async function goToStudents(page: Page) {
  // Client-side navigation (not `page.goto`) so the in-memory session
  // established by `loginAs` survives — a full navigation would drop it and
  // re-trigger `RouteGuard`'s own refresh call.
  await page.getByRole("link", { name: "Students" }).click();
  await expect(page).toHaveURL(/\/tenant-admin\/students$/, { timeout: NAV_TIMEOUT });
}

/**
 * Mocks a successful silent refresh for `role` and does nothing else — no
 * `/v1/auth/login` mock, no visit to `/login`. Establishes a session the same
 * way `RouteGuard` itself does on a fresh page load with no in-memory token
 * (a mocked silent refresh plus a direct navigation), deliberately bypassing
 * the login UI so these tests isolate dashboard-page behavior (permission
 * gating, read-only rendering) from the separate, already-covered login/
 * redirect flow (see "staff sub-role login" below). Not a workaround for any
 * known bug — `loginAs` itself works for every staff sub-role too.
 */
async function mockRefreshOnly(page: Page, role: string): Promise<void> {
  await mockJson(
    page,
    "**/v1/auth/refresh",
    200,
    apiSuccess(refreshResponseBody(fakeJwt({ role })))
  );
}

test.describe("student list — accessible labels", () => {
  test("search and status filter fields are associated with visible labels", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA, GRACE]));
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await expect(page.getByLabel("Search")).toBeVisible();
    await expect(page.getByLabel("Status")).toBeVisible();
  });
});

test.describe("student list — loading, error, and permission-denied states", () => {
  test("shows an accessible loading indicator while the list is in flight", async ({ page }) => {
    // Direct navigation (`mockRefreshOnly` + `page.goto`), not
    // `loginAs`/`goToStudents`: as of MVP-015 (Tenant Admin Dashboard,
    // TADASH-1), `/tenant-admin/dashboard` — the page `loginAs` lands on —
    // fires its own `GET /v1/students` read (`useStudents`, same endpoint
    // this test instruments below). Routing through the dashboard first
    // would let that unrelated read consume this test's single delayed
    // response before the Students List page's own request ever fires.
    await page.route(STUDENTS_ENDPOINT, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 500));
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope([ADA, GRACE])),
      });
    });
    await mockRefreshOnly(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/students");

    await expect(page.getByRole("status").filter({ hasText: "Loading students…" })).toBeVisible();
    // Scoped to the table: `DataTable` also renders a (CSS-hidden at this
    // desktop viewport) mobile card list with the same text, so an
    // unscoped `getByText` would resolve to two elements.
    await expect(page.getByRole("table").getByText("Ada Lovelace")).toBeVisible();
  });

  test("a server error renders the shared error state with a retry action", async ({ page }) => {
    // Direct navigation, not `loginAs`/`goToStudents` — see the identical
    // MVP-015 TADASH-1 note in the loading-indicator test above: this test's
    // `requestCount`-based mock must count only the Students List page's own
    // requests, not also the Overview dashboard's unrelated `GET
    // /v1/students` read.
    let requestCount = 0;
    await page.route(STUDENTS_ENDPOINT, async (route) => {
      requestCount += 1;
      // `QueryProvider`'s default `retry: 1` means a query fails, then React
      // Query silently retries once automatically before ever surfacing an
      // error — so a mock that only fails the very first request never
      // actually reaches the rendered `ErrorState`/"Try again" button, it
      // just self-heals via that automatic retry. Failing the first two
      // requests (the initial attempt + the one automatic retry) is what
      // actually exhausts retries and gets the UI into the error state this
      // test means to exercise; the 3rd request is the explicit "Try again"
      // click below.
      if (requestCount <= 2) {
        await route.fulfill({
          status: 500,
          contentType: "application/json",
          body: JSON.stringify({
            success: false,
            data: null,
            error: { code: "INTERNAL_ERROR", message: "Something went wrong.", fieldErrors: [] },
            timestamp: new Date().toISOString(),
            traceId: "test-trace-id",
          }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope([ADA, GRACE])),
      });
    });
    await mockRefreshOnly(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/students");

    const alert = page.getByRole("alert");
    await expect(alert).toBeVisible();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();

    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByRole("table").getByText("Ada Lovelace")).toBeVisible();
  });

  test("a 403 renders the permission-denied state, not a generic error", async ({ page }) => {
    await mockJson(
      page,
      STUDENTS_ENDPOINT,
      403,
      apiError("FORBIDDEN", "You do not have permission to view students.")
    );
    await mockRefreshOnly(page, "READ_ONLY_AUDITOR");
    await page.goto("/tenant-admin/students");

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByText("You do not have permission to view students.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to your dashboard" })).toBeVisible();
  });
});

test.describe("student list — two distinct empty states", () => {
  test("zero students in the tenant shows the 'no students yet' state with an Add student CTA", async ({
    page,
  }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([]));
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await expect(page.getByText("No students yet")).toBeVisible();
    // Two "Add student" affordances exist while this empty state is showing
    // (the page header button and the empty-state CTA) — either opens the
    // same sheet.
    const ctas = page.getByRole("button", { name: "Add student" });
    await expect(ctas).toHaveCount(2);

    await ctas.last().click();
    await expect(page.getByRole("dialog").getByRole("heading", { name: "Add student" })).toBeVisible();
  });

  test("a search/filter combination that excludes every row shows a distinct 'no match' state", async ({
    page,
  }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA, GRACE]));
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await page.getByLabel("Search").fill("no-such-student-zzz");

    await expect(page.getByText("No students match your filters")).toBeVisible();
    await expect(page.getByText("No students yet")).toHaveCount(0);

    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByRole("table").getByText("Ada Lovelace")).toBeVisible();
  });
});

test.describe("student list — client-side search and status filter", () => {
  test("filters the fetched list without any additional request (no server-side filter param exists)", async ({
    page,
  }) => {
    let requestCount = 0;
    await page.route(STUDENTS_ENDPOINT, async (route) => {
      requestCount += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope([ADA, GRACE])),
      });
    });
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    const table = page.getByRole("table");
    await expect(table.getByText("Ada Lovelace")).toBeVisible();
    await expect(table.getByText("Grace Hopper")).toBeVisible();

    await page.getByLabel("Search").fill("grace");
    await expect(table.getByText("Grace Hopper")).toBeVisible();
    await expect(table.getByText("Ada Lovelace")).toHaveCount(0);

    await page.getByLabel("Search").fill("");
    await page.getByLabel("Status").selectOption("SUSPENDED");
    await expect(table.getByText("Grace Hopper")).toBeVisible();
    await expect(table.getByText("Ada Lovelace")).toHaveCount(0);

    // Exactly one GET, on initial load — filtering never issues a new request.
    expect(requestCount).toBe(1);
  });
});

test.describe("student list — status is conveyed with an icon and text label", () => {
  test("active and suspended both render a visible text label, not color alone", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA, GRACE]));
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    const table = page.getByRole("table");
    await expect(table.getByText("Active", { exact: true })).toBeVisible();
    await expect(table.getByText("Suspended", { exact: true })).toBeVisible();
  });
});

test.describe("student list — responsive behavior", () => {
  test("below md, the table converts to a stacked card list", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA, GRACE]));
    await page.setViewportSize({ width: 375, height: 812 });
    await loginAs(page, "TENANT_ADMIN");
    // Not `goToStudents()`: below `md`, `DashboardShell` only renders the
    // sidebar nav (with the "Students" link) inside a closed-by-default
    // mobile `Sheet` drawer — not clickable without opening it first. This
    // test is about `DataTable`'s responsive markup, not mobile nav
    // interaction, so it navigates directly instead, relying on the refresh
    // mock `loginAs` already registered (the same mechanism `RouteGuard`
    // itself uses on a full navigation).
    await page.goto("/tenant-admin/students");

    await expect(page.getByRole("table")).toBeHidden();
    await expect(page.getByRole("list").getByText("Ada Lovelace")).toBeVisible();
  });

  test("at md and above, the table is shown instead of the card list", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA, GRACE]));
    await page.setViewportSize({ width: 1024, height: 800 });
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await expect(page.getByRole("table")).toBeVisible();
  });
});

test.describe("student list — role-based visibility of 'Add student' (UX convenience only)", () => {
  test("Tenant Admin sees the Add student action", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA, GRACE]));
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await expect(page.getByRole("button", { name: "Add student" })).toBeVisible();
  });

  test("Student Support sees the Add student action", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA, GRACE]));
    await mockRefreshOnly(page, "STUDENT_SUPPORT");
    await page.goto("/tenant-admin/students");

    await expect(page.getByRole("button", { name: "Add student" })).toBeVisible();
  });

  test("a view-only role (Read-only Auditor) does not see the Add student action", async ({
    page,
  }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA, GRACE]));
    await mockRefreshOnly(page, "READ_ONLY_AUDITOR");
    await page.goto("/tenant-admin/students");

    // Scoped to the table: `DataTable` also renders a (CSS-hidden at this
    // desktop viewport) mobile card list with the same text, so an unscoped
    // `getByText` resolves to two elements.
    await expect(page.getByRole("table").getByText("Ada Lovelace")).toBeVisible();
    await expect(page.getByRole("button", { name: "Add student" })).toHaveCount(0);
  });
});

test.describe("staff sub-role login", () => {
  test("a Student-Management staff sub-role (Read-only Auditor) signs in and reaches the Tenant Admin dashboard", async ({
    page,
  }) => {
    // `READ_ONLY_AUDITOR` is one of this module's own named staff sub-roles
    // (`.claude/rules/ui-ux.md` §1, `canManageStudents`'s sibling read-only
    // grant). `app/(auth)/login/page.tsx`'s `DASHBOARD_PATH_BY_ROLE` now maps
    // every staff sub-role to `/tenant-admin/dashboard`, same as
    // `TENANT_ADMIN` — per-page permission checks (this module's
    // `canManageStudents()`, `QueryStateBoundary`'s 403 handling) gate what
    // the sub-role can actually do once there, not the portal it lands in.
    const token = fakeJwt({ role: "READ_ONLY_AUDITOR" });
    await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(token)));
    await page.goto("/login");
    await page.getByLabel("Email").fill("staff@example-institute.test");
    await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
    await page.getByRole("button", { name: "Sign in" }).click();

    await expect(page).toHaveURL(/\/tenant-admin\/dashboard$/, { timeout: NAV_TIMEOUT });
  });
});

test.describe("add student — form and validation", () => {
  test("every field has an associated visible label", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([]));
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await page.getByRole("button", { name: "Add student" }).first().click();
    await expect(page.getByLabel("Name")).toBeVisible();
    await expect(page.getByLabel("Email")).toBeVisible();
    await expect(page.getByLabel("Password")).toBeVisible();
  });

  test("submitting an empty form shows inline required-field errors and makes no request", async ({
    page,
  }) => {
    let createRequestMade = false;
    await page.route(STUDENTS_ENDPOINT, async (route) => {
      if (route.request().method() === "POST") {
        createRequestMade = true;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope([])),
      });
    });
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await page.getByRole("button", { name: "Add student" }).first().click();
    await page.getByRole("button", { name: "Create student" }).click();

    await expect(page.getByText("Name is required.")).toBeVisible();
    await expect(page.getByText("Email is required.")).toBeVisible();
    await expect(page.getByText("Password must be at least 8 characters.")).toBeVisible();
    // Not just visible text — each input must be programmatically linked to
    // its own error via aria-describedby, or a screen-reader user gets no
    // association between the input and the message.
    await expect(page.getByLabel("Name")).toHaveAttribute(
      "aria-describedby",
      "create-student-name-error"
    );
    await expect(page.getByLabel("Email")).toHaveAttribute(
      "aria-describedby",
      "create-student-email-error"
    );
    expect(createRequestMade).toBe(false);
  });

  test("a short password is rejected client-side before submission", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([]));
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await page.getByRole("button", { name: "Add student" }).first().click();
    await page.getByLabel("Name").fill("New Student");
    await page.getByLabel("Email").fill("new-student@example-institute.test");
    await page.getByLabel("Password").fill("short");
    await page.getByRole("button", { name: "Create student" }).click();

    await expect(page.getByText("Password must be at least 8 characters.")).toBeVisible();
  });

  test("successful creation closes the sheet, refetches the list, and returns focus to the trigger", async ({
    page,
  }) => {
    let listCallCount = 0;
    await page.route(STUDENTS_ENDPOINT, async (route) => {
      const method = route.request().method();
      if (method === "POST") {
        await route.fulfill({
          status: 201,
          contentType: "application/json",
          body: JSON.stringify(
            envelope({
              id: "33333333-3333-3333-3333-333333333333",
              name: "New Student",
              email: "new-student@example-institute.test",
              roleCode: "STUDENT",
              status: "ACTIVE",
            })
          ),
        });
        return;
      }
      listCallCount += 1;
      const body = listCallCount === 1 ? [ADA] : [ADA, { ...GRACE, name: "New Student" }];
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(body)),
      });
    });
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    const trigger = page.getByRole("button", { name: "Add student" });
    await trigger.click();

    await page.getByLabel("Name").fill("New Student");
    await page.getByLabel("Email").fill("new-student@example-institute.test");
    await page.getByLabel("Password").fill("correct-horse-battery-staple");
    await page.getByRole("button", { name: "Create student" }).click();

    await expect(page.getByRole("dialog")).toBeHidden();
    await expect(trigger).toBeFocused();
    await expect(page.getByRole("table").getByText("New Student")).toBeVisible();
    expect(listCallCount).toBe(2);
  });

  test("a duplicate-email conflict (409) surfaces as a field-level error on email", async ({
    page,
  }) => {
    await page.route(STUDENTS_ENDPOINT, async (route) => {
      if (route.request().method() === "POST") {
        await route.fulfill({
          status: 409,
          contentType: "application/json",
          body: JSON.stringify({
            success: false,
            data: null,
            error: {
              code: "CONFLICT",
              message: "A student account with this email already exists",
              fieldErrors: [],
            },
            timestamp: new Date().toISOString(),
            traceId: "test-trace-id",
          }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope([ADA])),
      });
    });
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await page.getByRole("button", { name: "Add student" }).click();
    await page.getByLabel("Name").fill("Ada Lovelace");
    await page.getByLabel("Email").fill(ADA.email);
    await page.getByLabel("Password").fill("correct-horse-battery-staple");
    await page.getByRole("button", { name: "Create student" }).click();

    const emailField = page.getByLabel("Email");
    await expect(page.getByText("A student account with this email already exists")).toBeVisible();
    await expect(emailField).toHaveAttribute("aria-invalid", "true");
    await expect(page.getByRole("dialog")).toBeVisible();
  });

  test("a 403 from a stale UI surfaces an accessible alert instead of crashing", async ({ page }) => {
    await page.route(STUDENTS_ENDPOINT, async (route) => {
      if (route.request().method() === "POST") {
        await route.fulfill({
          status: 403,
          contentType: "application/json",
          body: JSON.stringify({
            success: false,
            data: null,
            error: { code: "FORBIDDEN", message: "You do not have permission to create students.", fieldErrors: [] },
            timestamp: new Date().toISOString(),
            traceId: "test-trace-id",
          }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope([ADA])),
      });
    });
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    await page.getByRole("button", { name: "Add student" }).click();
    await page.getByLabel("Name").fill("New Student");
    await page.getByLabel("Email").fill("new-student@example-institute.test");
    await page.getByLabel("Password").fill("correct-horse-battery-staple");
    await page.getByRole("button", { name: "Create student" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "You do not have permission to create students." })
    ).toBeVisible();
    // Still on the (open) sheet — no crash, no silent dismissal.
    await expect(page.getByRole("dialog")).toBeVisible();
  });

  test("the sheet is full-width below md and capped to max-w-md at md and above", async ({
    page,
  }) => {
    // Confirms the actual rendered width, not just the class names present —
    // the full-screen-on-mobile override depends on tailwind-merge correctly
    // resolving a conflicting `sm`/`md`-prefixed max-width utility pair, per
    // the styles in create-student-sheet.tsx/sheet.tsx; asserting rendered
    // width catches a regression there that reading class names would not.
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([]));
    await loginAs(page, "TENANT_ADMIN");

    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/tenant-admin/students");
    await page.getByRole("button", { name: "Add student" }).first().click();
    const mobileBox = await page.getByRole("dialog").boundingBox();
    expect(mobileBox?.width).toBeGreaterThan(360);
    await page.getByRole("button", { name: "Cancel" }).click();

    // 640-767px: inside Tailwind's `sm` range but below `md`. A prior
    // regression here (base component's `sm:max-w-sm` shipping alongside an
    // `md:max-w-md` override, which tailwind-merge does not dedupe against
    // each other) capped the sheet at ~384px in this band instead of
    // full-width — this viewport specifically catches that class of bug,
    // which the mobile/desktop-only probes above do not.
    await page.setViewportSize({ width: 700, height: 800 });
    await page.getByRole("button", { name: "Add student" }).first().click();
    const smRangeBox = await page.getByRole("dialog").boundingBox();
    expect(smRangeBox?.width).toBeGreaterThan(650);
    await page.getByRole("button", { name: "Cancel" }).click();

    // Same page, no re-navigation needed — resizing alone crosses the `md`
    // breakpoint. `.first()` because the empty-state "Add student" CTA (the
    // list is mocked empty) shares the same accessible name as the
    // page-header trigger.
    await page.setViewportSize({ width: 1024, height: 800 });
    await page.getByRole("button", { name: "Add student" }).first().click();
    const desktopBox = await page.getByRole("dialog").boundingBox();
    expect(desktopBox?.width).toBeLessThan(500);
  });
});

test.describe("student detail/edit", () => {
  async function mockStudentDetail(page: Page, student: typeof ADA) {
    await mockJson(page, `**/v1/students/${student.id}`, 200, envelope(student));
  }

  test("row 'View' action navigates to the student's detail page", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA, GRACE]));
    await mockStudentDetail(page, ADA);
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);

    const table = page.getByRole("table");
    await table
      .locator("tbody tr")
      .filter({ hasText: "Ada Lovelace" })
      .getByRole("link", { name: "View" })
      .click();
    await expect(page).toHaveURL(new RegExp(`/tenant-admin/students/${ADA.id}$`), {
      timeout: NAV_TIMEOUT,
    });
    await expect(page.getByRole("heading", { name: "Ada Lovelace" })).toBeVisible();
  });

  test("renders read-only email, status, and role, and an editable name field", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA]));
    await mockStudentDetail(page, ADA);
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);
    await page.getByRole("link", { name: "View" }).click();
    await expect(page).toHaveURL(new RegExp(`/tenant-admin/students/${ADA.id}$`), {
      timeout: NAV_TIMEOUT,
    });

    await expect(page.getByText(ADA.email)).toBeVisible();
    await expect(page.getByText("Active", { exact: true })).toBeVisible();
    await expect(page.getByText("STUDENT", { exact: true })).toBeVisible();
    await expect(page.getByLabel("Name")).toHaveValue("Ada Lovelace");
  });

  test("saving a name change calls PATCH and announces success", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA]));
    let patchBody: unknown = null;
    await page.route(`**/v1/students/${ADA.id}`, async (route) => {
      if (route.request().method() === "PATCH") {
        patchBody = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(envelope({ ...ADA, name: "Ada L. Byron" })),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(ADA)),
      });
    });
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);
    await page.getByRole("link", { name: "View" }).click();
    await expect(page).toHaveURL(new RegExp(`/tenant-admin/students/${ADA.id}$`), {
      timeout: NAV_TIMEOUT,
    });

    const nameField = page.getByLabel("Name");
    await nameField.fill("Ada L. Byron");
    await page.getByRole("button", { name: "Save changes" }).click();

    // Two "Saved." nodes exist by design: an `aria-live` sr-only announcement
    // for assistive tech, and a visible confirmation for sighted users —
    // assert the visible one specifically (last in DOM order).
    await expect(page.getByText("Saved.").last()).toBeVisible();
    expect(patchBody).toEqual({ name: "Ada L. Byron" });
  });

  test("saving announces the busy state while the request is in flight", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA]));
    await page.route(`**/v1/students/${ADA.id}`, async (route) => {
      if (route.request().method() === "PATCH") {
        await new Promise((resolve) => setTimeout(resolve, 500));
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(envelope({ ...ADA, name: "Ada L. Byron" })),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(ADA)),
      });
    });
    await loginAs(page, "TENANT_ADMIN");
    await goToStudents(page);
    await page.getByRole("link", { name: "View" }).click();
    await expect(page).toHaveURL(new RegExp(`/tenant-admin/students/${ADA.id}$`), {
      timeout: NAV_TIMEOUT,
    });

    await page.getByLabel("Name").fill("Ada L. Byron");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByRole("status").filter({ hasText: "Saving…" })).toBeVisible();
    await expect(page.locator("form[aria-busy='true']")).toBeVisible();
    await expect(page.getByText("Saved.").last()).toBeVisible();
  });

  test("a uniform 404 (nonexistent or cross-tenant id) renders the shared error state with the backend's message", async ({
    page,
  }) => {
    await mockJson(
      page,
      "**/v1/students/99999999-9999-9999-9999-999999999999",
      404,
      apiError("NOT_FOUND", "Student account not found")
    );
    await loginAs(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/students/99999999-9999-9999-9999-999999999999");

    await expect(
      page.getByRole("alert").filter({ hasText: "Student account not found" })
    ).toBeVisible({ timeout: NAV_TIMEOUT });
  });

  test("a view-only role sees the record read-only, with no editable name field", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA]));
    await mockStudentDetail(page, ADA);
    await mockRefreshOnly(page, "READ_ONLY_AUDITOR");
    await page.goto(`/tenant-admin/students/${ADA.id}`);

    await expect(page.getByText(ADA.email)).toBeVisible({ timeout: NAV_TIMEOUT });
    await expect(page.getByLabel("Name")).toHaveCount(0);
    await expect(
      page.getByText("You don't have permission to edit this student's details.")
    ).toBeVisible();
  });

  test("a 403 on save surfaces an accessible alert instead of crashing", async ({ page }) => {
    await mockJson(page, STUDENTS_ENDPOINT, 200, envelope([ADA]));
    await page.route(`**/v1/students/${ADA.id}`, async (route) => {
      if (route.request().method() === "PATCH") {
        await route.fulfill({
          status: 403,
          contentType: "application/json",
          body: JSON.stringify({
            success: false,
            data: null,
            error: { code: "FORBIDDEN", message: "You do not have permission to edit this student.", fieldErrors: [] },
            timestamp: new Date().toISOString(),
            traceId: "test-trace-id",
          }),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(envelope(ADA)),
      });
    });
    await loginAs(page, "TENANT_ADMIN");
    await page.goto(`/tenant-admin/students/${ADA.id}`);

    await expect(page.getByLabel("Name")).toBeVisible({ timeout: NAV_TIMEOUT });
    await page.getByLabel("Name").fill("Someone Else");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "You do not have permission to edit this student." })
    ).toBeVisible();
  });
});
