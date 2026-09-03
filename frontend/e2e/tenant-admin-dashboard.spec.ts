import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  fulfillJson,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * MVP-015 Tenant Admin Dashboard — Overview (`/tenant-admin/dashboard`,
 * TADASH-1 — rebuild of the prior static placeholder).
 *
 * Three independent, already-tenant-scoped reads (`GET /v1/students`,
 * `GET /api/v1/courses?page=0&size=1` + `GET /api/v1/courses?status=PUBLIC&
 * page=0&size=1` combined by `useTenantCourseCounts`, `GET
 * /api/v1/ledger/dashboard?page=0&size=1`) composed client-side into three
 * `StatCard`s, each behind its own `QueryStateBoundary` so one domain's
 * loading/error/empty state never affects the other two (plan §4.1 steps
 * 4-5). No real backend runs in this environment (see
 * `fixtures/auth-mocks.ts`'s module doc) — every test mocks `/v1/**`/
 * `/api/v1/**` responses shaped like the documented `ApiResponse<T>`
 * envelope.
 *
 * Note (per `lib/api/students.ts`'s own doc comment): `GET /v1/students` has
 * no `/api` prefix, unlike the other two reads — a pre-existing
 * inconsistency in this codebase, not something this test works around or
 * fixes.
 *
 * Payments-card role-gating (`canViewPaymentDashboard(role)`, review's H1
 * finding): the Payments card and its `GET /api/v1/ledger/dashboard` read
 * only render/fire for 4 of the 8 in-scope roles (Tenant Admin, Finance
 * Staff, Student Support, Read-only Auditor) — see the dedicated
 * "Payments card role-gating" describe block below, which also proves the
 * fetch itself never fires for a role without the grant, not just that the
 * card is hidden after a 403. Every OTHER describe block in this file uses
 * `TENANT_ADMIN`, which holds the grant, so the Payments card renders for
 * all of them as before.
 */

/** Establishes a session for the given role by mocking `POST /v1/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

function makeStudents(count: number) {
  return Array.from({ length: count }, (_, i) => ({
    id: `student-${i + 1}`,
    name: `Student ${i + 1}`,
    email: `student${i + 1}@example.com`,
    roleCode: "STUDENT",
    status: "ACTIVE",
  }));
}

/**
 * Mocks `GET /api/v1/courses?page=0&size=1` (total) and `GET
 * /api/v1/courses?status=PUBLIC&page=0&size=1` (published) with two
 * different `totalElements`, routing on the `status=PUBLIC` query param
 * since both reads share the same base path.
 */
async function mockCourseCounts(
  page: Page,
  { total, published }: { total: number; published: number }
): Promise<void> {
  await page.route("**/api/v1/courses*", async (route) => {
    const url = route.request().url();
    const totalElements = url.includes("status=PUBLIC") ? published : total;
    await fulfillJson(route, 200, apiPageSuccess([], { totalElements }));
  });
}

async function mockLedgerCount(page: Page, totalElements: number): Promise<void> {
  await mockJson(
    page,
    "**/api/v1/ledger/dashboard*",
    200,
    apiPageSuccess([], { totalElements })
  );
}

test.describe("tenant admin overview — populated state", () => {
  test("renders correct Students/Courses/Payments counts computed from the three independent reads", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess(makeStudents(5)));
    await mockCourseCounts(page, { total: 10, published: 6 });
    await mockLedgerCount(page, 12);

    await page.goto("/tenant-admin/dashboard");

    const studentsCard = page.getByRole("group", { name: "Total Students" });
    await expect(studentsCard).toContainText("5");

    const coursesCard = page.getByRole("group", { name: "Total Courses" });
    await expect(coursesCard).toContainText("10");
    // draft = total - published = 10 - 6 = 4.
    await expect(coursesCard).toContainText("6 published, 4 draft");

    const paymentsCard = page.getByRole("group", { name: "Payment Entries Recorded" });
    await expect(paymentsCard).toContainText("12");
    await expect(paymentsCard).toContainText(
      "Entries recorded in the ledger. This is a count, not a currency amount."
    );
  });
});

test.describe("tenant admin overview — independent per-card empty state", () => {
  test("all three reads returning genuine zero renders three independent '0 + hint' cards, not one page-level empty state", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess([]));
    await mockCourseCounts(page, { total: 0, published: 0 });
    await mockLedgerCount(page, 0);

    await page.goto("/tenant-admin/dashboard");

    const studentsCard = page.getByRole("group", { name: "Total Students" });
    await expect(studentsCard).toContainText("0");
    await expect(studentsCard).toContainText("No students enrolled yet");
    const addStudentLink = studentsCard.getByRole("link", { name: "Add a student" });
    await expect(addStudentLink).toBeVisible();
    await expect(addStudentLink).toHaveAttribute("href", "/tenant-admin/students");

    const coursesCard = page.getByRole("group", { name: "Total Courses" });
    await expect(coursesCard).toContainText("0");
    await expect(coursesCard).toContainText("No courses created yet");
    const createCourseLink = coursesCard.getByRole("link", { name: "Create a course" });
    await expect(createCourseLink).toBeVisible();
    await expect(createCourseLink).toHaveAttribute("href", "/tenant-admin/courses");

    const paymentsCard = page.getByRole("group", { name: "Payment Entries Recorded" });
    await expect(paymentsCard).toContainText("0");
    await expect(paymentsCard).toContainText(
      "No payment entries recorded yet. This is a count, not a currency amount."
    );
    // Deliberately no CTA on the Payments card — unlike Students/Courses,
    // there is no Tenant-Admin-initiated "record a payment" action reachable
    // from this dashboard.
    await expect(paymentsCard.getByRole("link")).toHaveCount(0);
  });

  test("a mixed case (Students empty, Courses/Payments populated) proves the empty state is per-card, not whole-page", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess([]));
    await mockCourseCounts(page, { total: 8, published: 5 });
    await mockLedgerCount(page, 20);

    await page.goto("/tenant-admin/dashboard");

    const studentsCard = page.getByRole("group", { name: "Total Students" });
    await expect(studentsCard).toContainText("0");
    await expect(studentsCard).toContainText("No students enrolled yet");

    const coursesCard = page.getByRole("group", { name: "Total Courses" });
    await expect(coursesCard).toContainText("8");
    await expect(coursesCard).toContainText("5 published, 3 draft");
    await expect(coursesCard).not.toContainText("No courses created yet");

    const paymentsCard = page.getByRole("group", { name: "Payment Entries Recorded" });
    await expect(paymentsCard).toContainText("20");
    await expect(paymentsCard).toContainText(
      "Entries recorded in the ledger. This is a count, not a currency amount."
    );
    await expect(paymentsCard).not.toContainText("No payment entries recorded yet");
  });
});

test.describe("tenant admin overview — independent per-card loading state", () => {
  test("gating only the ledger read in flight does not block the Students/Courses cards from rendering their real data", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess(makeStudents(3)));
    await mockCourseCounts(page, { total: 5, published: 2 });

    let releaseLedger: (() => void) | undefined;
    const ledgerGate = new Promise<void>((resolve) => {
      releaseLedger = resolve;
    });
    await page.route("**/api/v1/ledger/dashboard*", async (route) => {
      await ledgerGate;
      await fulfillJson(route, 200, apiPageSuccess([], { totalElements: 9 }));
    });

    await page.goto("/tenant-admin/dashboard");

    const ledgerStatus = page.getByRole("status").filter({ hasText: "Loading payments recorded…" });
    await expect(ledgerStatus).toBeVisible();
    await expect(ledgerStatus).toHaveAttribute("aria-busy", "true");
    await expect(ledgerStatus).toHaveAttribute("aria-live", "polite");

    // The other two cards' reads are not gated and must already show real
    // data while the ledger card is still loading — this is the key
    // regression proof that one card's in-flight read never blocks the
    // other two (plan §4.1 steps 4-5).
    await expect(page.getByRole("group", { name: "Total Students" })).toContainText("3");
    await expect(page.getByRole("group", { name: "Total Courses" })).toContainText("5");

    releaseLedger?.();
    await expect(page.getByText("Loading payments recorded…")).toHaveCount(0);
    await expect(page.getByRole("group", { name: "Payment Entries Recorded" })).toContainText("9");
  });
});

test.describe("tenant admin overview — independent per-card error state", () => {
  test("a failed ledger read shows a retryable error without blanking the Students/Courses cards, and Retry recovers", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess(makeStudents(3)));
    await mockCourseCounts(page, { total: 5, published: 2 });
    // Fails on every attempt, including React Query's default automatic
    // retry, so the UI settles into a real, observable error state.
    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      500,
      apiError("INTERNAL_ERROR", "Could not load payments recorded.")
    );

    await page.goto("/tenant-admin/dashboard");

    const errorAlert = page.getByRole("alert").filter({ hasText: "Could not load payments recorded." });
    await expect(errorAlert).toBeVisible();

    // The other two cards' successful reads are unaffected by the failing
    // ledger card.
    await expect(page.getByRole("group", { name: "Total Students" })).toContainText("3");
    await expect(page.getByRole("group", { name: "Total Courses" })).toContainText("5");

    await mockJson(
      page,
      "**/api/v1/ledger/dashboard*",
      200,
      apiPageSuccess([], { totalElements: 9 })
    );
    await errorAlert.getByRole("button", { name: "Try again" }).click();

    await expect(page.getByRole("group", { name: "Payment Entries Recorded" })).toContainText("9");
    await expect(errorAlert).toHaveCount(0);
    // Still unaffected after the retry resolves.
    await expect(page.getByRole("group", { name: "Total Students" })).toContainText("3");
    await expect(page.getByRole("group", { name: "Total Courses" })).toContainText("5");
  });
});

test.describe("tenant admin overview — cross-tenant scoping (presentational proof)", () => {
  test("renders exactly the caller's own tenant-scoped counts, never an aggregated/inflated figure", async ({
    page,
  }) => {
    // Every read this page performs (`GET /v1/students`, `GET
    // /api/v1/courses`, `GET /api/v1/ledger/dashboard`) already resolves
    // tenant identity exclusively from the authenticated request context on
    // the backend, and this module introduces no new endpoint (plan §14).
    // Since no real backend runs in this environment, this test cannot
    // exercise a genuine two-tenant DB fixture — that proof lives in the
    // backend integration suites for these same three endpoints
    // (`StudentManagementIntegrationTest`, `CourseManagementIntegrationTest`,
    // ledger's own cross-tenant test, per plan §18). This test's job is
    // narrower: prove the page renders exactly what its three scoped reads
    // return, with no client-side aggregation, inflation, or a second,
    // less-filtered fetch layered on top (plan §15's "KPI figures must never
    // be computed from a broader/unfiltered fetch narrowed client-side").
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess(makeStudents(3)));
    await mockCourseCounts(page, { total: 4, published: 2 });
    await mockLedgerCount(page, 6);

    await page.goto("/tenant-admin/dashboard");

    await expect(page.getByRole("group", { name: "Total Students" })).toContainText("3");
    await expect(page.getByRole("group", { name: "Total Courses" })).toContainText("4");
    await expect(page.getByRole("group", { name: "Payment Entries Recorded" })).toContainText("6");
  });
});

test.describe("tenant admin overview — no tenant selector regression", () => {
  test("renders no tenant selector/switcher element anywhere on the page", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess([]));
    await mockCourseCounts(page, { total: 0, published: 0 });
    await mockLedgerCount(page, 0);

    await page.goto("/tenant-admin/dashboard");

    await expect(page.getByRole("group", { name: "Total Students" })).toBeVisible();
    await expect(page.getByRole("combobox")).toHaveCount(0);
    await expect(page.getByRole("listbox")).toHaveCount(0);
  });
});

test.describe("tenant admin overview — responsive grid", () => {
  test("the stat card grid actually lays out 1/2/3 columns at base/sm/lg widths", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess(makeStudents(2)));
    await mockCourseCounts(page, { total: 3, published: 1 });
    await mockLedgerCount(page, 4);

    await page.goto("/tenant-admin/dashboard");

    const studentsCard = page.getByRole("group", { name: "Total Students" });
    await expect(studentsCard).toBeVisible();
    // `grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3` — the three
    // StatCards' shared parent.
    const grid = studentsCard.locator("xpath=..");

    async function columnCount() {
      return grid.evaluate(
        (el) => getComputedStyle(el).gridTemplateColumns.split(" ").filter(Boolean).length
      );
    }

    await page.setViewportSize({ width: 375, height: 900 });
    await expect.poll(columnCount).toBe(1);

    await page.setViewportSize({ width: 700, height: 900 });
    await expect.poll(columnCount).toBe(2);

    await page.setViewportSize({ width: 1280, height: 900 });
    await expect.poll(columnCount).toBe(3);
  });

  test("for a role without the Payments grant (2 cards, not 3), the grid caps at 2 columns at lg and above", async ({
    page,
  }) => {
    // Course Coordinator does not hold `PAYMENTS_SLIPS`/`VIEW`
    // (`canViewPaymentDashboard`) — the Payments card never renders, so
    // `page.tsx`'s grid must switch to `lg:grid-cols-2` instead of the
    // 3-card default `lg:grid-cols-3`; asserting a hardcoded 3 here would
    // pass even if that conditional column count regressed to always-3.
    await mockTenantSession(page, "COURSE_COORDINATOR");
    await mockJson(page, "**/v1/students", 200, apiSuccess(makeStudents(2)));
    await mockCourseCounts(page, { total: 3, published: 1 });

    await page.goto("/tenant-admin/dashboard");

    const studentsCard = page.getByRole("group", { name: "Total Students" });
    await expect(studentsCard).toBeVisible();
    await expect(page.getByRole("group", { name: "Payment Entries Recorded" })).toHaveCount(0);
    const grid = studentsCard.locator("xpath=..");

    async function columnCount() {
      return grid.evaluate(
        (el) => getComputedStyle(el).gridTemplateColumns.split(" ").filter(Boolean).length
      );
    }

    await page.setViewportSize({ width: 1280, height: 900 });
    await expect.poll(columnCount).toBe(2);
  });
});

test.describe("tenant admin overview — RouteGuard", () => {
  test("an unauthenticated visit to /tenant-admin/dashboard redirects to /login before portal chrome renders", async ({
    page,
  }) => {
    // No session mocked at all: `POST /v1/auth/refresh` fails, so
    // `RouteGuard` redirects instead of resolving `ready`.
    await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session."));

    await page.goto("/tenant-admin/dashboard");

    await expect(page).toHaveURL(/\/login(\?|$)/);
    await expect(page.getByRole("heading", { name: "Overview" })).toHaveCount(0);
  });
});

/**
 * Payments card role-gating (review's H1 finding). `canViewPaymentDashboard`
 * gates both the card's rendering AND `useLedgerDashboard`'s `enabled` flag
 * (`page.tsx`, `lib/api/ledger.ts`) — 4 of the 8 in-scope roles never hold
 * `PAYMENTS_SLIPS`/`VIEW`, so `GET /api/v1/ledger/dashboard` must never be
 * requested for them, not merely hidden after a 403. The Students/Courses
 * cards are never gated by role (plan's Grounding note) and must render
 * normally regardless.
 */
const PAYMENT_CARD_VISIBILITY: Array<{ role: string; visible: boolean }> = [
  { role: "TENANT_ADMIN", visible: true },
  { role: "FINANCE_STAFF", visible: true },
  { role: "COURSE_COORDINATOR", visible: false },
  { role: "STUDENT_SUPPORT", visible: true },
  { role: "CONTENT_MANAGER", visible: false },
  { role: "EXAM_MANAGER", visible: false },
  { role: "ATTENDANCE_OPERATOR", visible: false },
  { role: "READ_ONLY_AUDITOR", visible: true },
];

test.describe("tenant admin overview — Payments card role-gating (canViewPaymentDashboard)", () => {
  for (const { role, visible } of PAYMENT_CARD_VISIBILITY) {
    test(`${role}: Payments card is ${
      visible ? "rendered and its read fires" : "not rendered and its read never fires"
    }`, async ({ page }) => {
      await mockTenantSession(page, role);
      await mockJson(page, "**/v1/students", 200, apiSuccess(makeStudents(2)));
      await mockCourseCounts(page, { total: 3, published: 1 });

      let ledgerRequestCount = 0;
      await page.route("**/api/v1/ledger/dashboard*", async (route) => {
        ledgerRequestCount += 1;
        await fulfillJson(route, 200, apiPageSuccess([], { totalElements: 7 }));
      });

      await page.goto("/tenant-admin/dashboard");

      // Students/Courses render regardless of this role's Payments grant —
      // proves the gate is scoped to the Payments card only.
      await expect(page.getByRole("group", { name: "Total Students" })).toContainText("2");
      await expect(page.getByRole("group", { name: "Total Courses" })).toContainText("3");

      const paymentsCard = page.getByRole("group", { name: "Payment Entries Recorded" });
      if (visible) {
        await expect(paymentsCard).toBeVisible();
        await expect(paymentsCard).toContainText("7");
        expect(ledgerRequestCount).toBeGreaterThan(0);
      } else {
        await expect(paymentsCard).toHaveCount(0);
        // The Students/Courses assertions above already forced a render pass
        // past the point where an `enabled: true` ledger query would have
        // dispatched its fetch — so by now, a fetch that was going to fire
        // already would have. Asserting 0 here proves `enabled: false`
        // actually suppressed the request, not just that the card is
        // visually hidden after a 403 that did fire.
        expect(ledgerRequestCount).toBe(0);
      }

      // Subtitle text mirrors the same `canViewPaymentDashboard` gate as the
      // Payments card itself (`page.tsx`'s conditional subtitle) — a role
      // without the grant must not have its subtitle mention payments at
      // all, since the card promising that data was never rendered.
      const subtitle = visible
        ? "Your institute's students, courses, and payments at a glance."
        : "Your institute's students and courses at a glance.";
      await expect(page.getByText(subtitle, { exact: true })).toBeVisible();
      if (!visible) {
        await expect(page.getByText("payments", { exact: false })).toHaveCount(0);
      }
    });
  }
});

/**
 * Overview's own `PermissionDeniedState` coverage (review's High finding —
 * previously zero coverage). `permissionDenied={{ dashboardHref:
 * "/tenant-admin/dashboard" }}` was removed from all three boundaries on
 * this page (a dead self-referential link, since this IS
 * `/tenant-admin/dashboard`) — `PermissionDeniedState` must render here with
 * no "Back to your dashboard" link/action.
 */
test.describe("tenant admin overview — permission-denied state (per-card, dead 'back' link removed)", () => {
  test("a 403 on the Students read renders PermissionDeniedState in that card only, with no 'Back to your dashboard' link, while Courses/Payments still render from their own successful reads", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/students",
      403,
      apiError("FORBIDDEN", "You do not have permission to view students.")
    );
    await mockCourseCounts(page, { total: 4, published: 2 });
    await mockLedgerCount(page, 9);

    await page.goto("/tenant-admin/dashboard");

    const deniedCard = page
      .getByRole("alert")
      .filter({ hasText: "You don't have permission to view this." });
    await expect(deniedCard).toBeVisible();
    await expect(deniedCard).toContainText("You do not have permission to view students.");
    // The dead self-referential `dashboardHref` prop was removed from this
    // page's boundaries — confirm no "back" link renders here at all.
    await expect(deniedCard.getByRole("link", { name: "Back to your dashboard" })).toHaveCount(0);
    await expect(deniedCard.getByRole("link")).toHaveCount(0);

    // The other two cards' independent, successful reads are unaffected.
    await expect(page.getByRole("group", { name: "Total Courses" })).toContainText("4");
    await expect(page.getByRole("group", { name: "Payment Entries Recorded" })).toContainText("9");
  });

  /**
   * The Courses card is powered by `useTenantCourseCounts()`
   * (`lib/api/tenant-overview.ts`), which is NOT a plain `useQuery` like the
   * Students/Payments cards above — it's a `useQueries`/`combine()` pair
   * (`GET /api/v1/courses?page=0&size=1` + `GET /api/v1/courses?status=
   * PUBLIC&page=0&size=1`) whose combined `status`/`error` are hand-derived
   * (`error: totalResult.error ?? publishedResult.error ?? null`). This is an
   * architecturally distinct error-propagation path from the Students card's
   * test above and has never been proven to correctly surface a real 403
   * through to `PermissionDeniedState`. Only the "total" variant (the
   * request lacking `status=PUBLIC`) is mocked as a 403 here — the
   * "published" variant still succeeds — specifically to prove `combine()`'s
   * `??` fallback surfaces an error even when just one of the two underlying
   * queries fails, not only when both do.
   */
  test("a 403 on the Courses (total) read renders PermissionDeniedState in that card only, while Students/Payments still render from their own successful reads", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess(makeStudents(3)));
    await page.route("**/api/v1/courses*", async (route) => {
      const url = route.request().url();
      if (url.includes("status=PUBLIC")) {
        await fulfillJson(route, 200, apiPageSuccess([], { totalElements: 5 }));
        return;
      }
      await fulfillJson(
        route,
        403,
        apiError("FORBIDDEN", "You do not have permission to view courses.")
      );
    });
    await mockLedgerCount(page, 9);

    await page.goto("/tenant-admin/dashboard");

    const coursesCard = page.getByRole("group", { name: "Total Courses" });
    await expect(coursesCard).toHaveCount(0);
    const deniedCard = page
      .getByRole("alert")
      .filter({ hasText: "You don't have permission to view this." });
    await expect(deniedCard).toBeVisible();
    await expect(deniedCard).toContainText("You do not have permission to view courses.");
    await expect(deniedCard.getByRole("link")).toHaveCount(0);

    // The other two cards' independent, successful reads are unaffected.
    await expect(page.getByRole("group", { name: "Total Students" })).toContainText("3");
    await expect(page.getByRole("group", { name: "Payment Entries Recorded" })).toContainText("9");
  });
});

/**
 * Per-card `aria-busy`/`role="alert"` wiring for the Students and Courses
 * cards specifically (review's L4 finding) — the existing loading/error
 * tests above only ever gate the Payments/ledger card, so a wiring
 * regression specific to the Students or Courses `QueryStateBoundary` would
 * not have been caught. Mirrors the existing ledger-gated tests' exact
 * promise-gate / persistent-failure patterns.
 */
test.describe("tenant admin overview — Students/Courses card loading and error wiring", () => {
  test("gating only the Students read in flight surfaces aria-busy/aria-live on that card without blocking Courses/Payments", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockCourseCounts(page, { total: 5, published: 3 });
    await mockLedgerCount(page, 6);

    let releaseStudents: (() => void) | undefined;
    const studentsGate = new Promise<void>((resolve) => {
      releaseStudents = resolve;
    });
    await page.route("**/v1/students", async (route) => {
      await studentsGate;
      await fulfillJson(route, 200, apiSuccess(makeStudents(4)));
    });

    await page.goto("/tenant-admin/dashboard");

    const studentsStatus = page.getByRole("status").filter({ hasText: "Loading student count…" });
    await expect(studentsStatus).toBeVisible();
    await expect(studentsStatus).toHaveAttribute("aria-busy", "true");
    await expect(studentsStatus).toHaveAttribute("aria-live", "polite");

    // The other two cards' unrelated reads are not gated and already show
    // real data while the Students card is still loading.
    await expect(page.getByRole("group", { name: "Total Courses" })).toContainText("5");
    await expect(page.getByRole("group", { name: "Payment Entries Recorded" })).toContainText("6");

    releaseStudents?.();
    await expect(page.getByText("Loading student count…")).toHaveCount(0);
    await expect(page.getByRole("group", { name: "Total Students" })).toContainText("4");
  });

  test("a failed Courses read shows a retryable error without blanking Students/Payments, and Retry recovers", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/students", 200, apiSuccess(makeStudents(3)));
    await mockLedgerCount(page, 6);
    // Fails on every attempt, including React Query's default automatic
    // retry, so the UI settles into a real, observable error state.
    await mockJson(
      page,
      "**/api/v1/courses*",
      500,
      apiError("INTERNAL_ERROR", "Could not load course counts.")
    );

    await page.goto("/tenant-admin/dashboard");

    const errorAlert = page.getByRole("alert").filter({ hasText: "Could not load course counts." });
    await expect(errorAlert).toBeVisible();

    // The other two cards' successful reads are unaffected by the failing
    // Courses card.
    await expect(page.getByRole("group", { name: "Total Students" })).toContainText("3");
    await expect(page.getByRole("group", { name: "Payment Entries Recorded" })).toContainText("6");

    await mockCourseCounts(page, { total: 8, published: 4 });
    await errorAlert.getByRole("button", { name: "Try again" }).click();

    await expect(page.getByRole("group", { name: "Total Courses" })).toContainText("8");
    await expect(errorAlert).toHaveCount(0);
    // Still unaffected after the retry resolves.
    await expect(page.getByRole("group", { name: "Total Students" })).toContainText("3");
    await expect(page.getByRole("group", { name: "Payment Entries Recorded" })).toContainText("6");
  });
});
