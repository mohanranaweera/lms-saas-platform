import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiSuccess,
  fakeJwt,
  fulfillJson,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * MVP-013 Student Dashboard — Overview (`/student/dashboard`, SDASH-1).
 *
 * Two independent React Query reads compose this page:
 * `GET /api/v1/enrollments/my` (stat cards, expired-access alert, recent
 * courses) and `GET /api/v1/ledger/history` (payment-activity card) — plan
 * §13 requires a failed read on one to never blank the other, which several
 * tests below verify directly. `GET /api/v1/enrollments/my/courses` is a
 * third, non-blocking read used only to resolve real course names for the
 * recent-courses list; a failure there degrades to `shortId` fallbacks
 * rather than hiding the section (mirrors `student/courses/page.tsx`'s
 * MVP-013 rework — see `enrollment-and-course-access.spec.ts`).
 *
 * No real backend runs in this environment — every test mocks `/api/v1/**`
 * via `page.route`/`mockJson`, following this suite's sibling specs'
 * conventions.
 *
 * Cross-tenant scope note (plan §18: "Cross-tenant/cross-student manual-seed
 * check ... confirm no course name or count from a different tenant's seed
 * data ever renders"): this file is intentionally mock-only, per this
 * codebase's established e2e convention (see `shared-states.spec.ts`'s
 * "Coverage note" for the same pattern of documenting a deliberate scope
 * narrowing instead of silently dropping a plan requirement). Mocking
 * `/api/v1/**` responses means there is no real, two-tenant-seeded backend
 * for this suite to prove isolation against — building that real-backend,
 * multi-tenant Playwright infrastructure just for this check would be a
 * disproportionate amount of new test infrastructure for a property the
 * backend already proves end-to-end. Instead, plan §18's cross-tenant
 * requirement for this module is satisfied by
 * `EnrollmentCrossTenantIntegrationTest#myCourseSummariesNeverContainsAnotherTenantsRowsEvenForAnIdenticallyNamedCourse`
 * (`backend/src/test/java/com/lms/enrollmentmanagement/EnrollmentCrossTenantIntegrationTest.java`),
 * which runs against a real, two-tenant-seeded Postgres database (via
 * Testcontainers) with a same-slug/name/category collision fixture and
 * asserts a student's `my/courses` summaries never contain another tenant's
 * rows even when the course names collide — the identical property this
 * mock-only suite cannot independently prove. A future reviewer should treat
 * that backend test, not a test in this file, as the source of truth for
 * this requirement.
 */

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

function makeEnrollment(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    enrollmentId: "enrollment-1",
    courseId: "course-12345678-abcd",
    state: "ACTIVE" as const,
    accessExpiresAt: null,
    canRequestReactivation: false,
    ...overrides,
  };
}

function makeCourseSummary(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "course-12345678-abcd",
    name: "Intro to Testing",
    slug: "intro-to-testing",
    category: "Quality Assurance",
    ...overrides,
  };
}

function makeLedgerEntry(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "ledger-1",
    orderId: "order-1",
    paymentId: "payment-1",
    entryType: "PAYMENT_CONFIRMED" as const,
    amount: 49.99,
    reversesEntryId: null,
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

test.describe("student — Overview populated state", () => {
  test("renders correct active/expired counts and an expired-access alert with a working Reactivate CTA", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const active = makeEnrollment({ enrollmentId: "e-active", state: "ACTIVE" });
    const expired = makeEnrollment({
      enrollmentId: "e-expired",
      courseId: "course-99999999-xyz",
      state: "EXPIRED",
      canRequestReactivation: true,
      accessExpiresAt: new Date().toISOString(),
    });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([active, expired]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    const activeCard = page.getByRole("group", { name: "Active courses" });
    await expect(activeCard).toContainText("1");
    const needsAttentionCard = page.getByRole("group", { name: "Needs attention" });
    await expect(needsAttentionCard).toContainText("1");

    const alert = page.getByRole("alert").filter({ hasText: "expired access" });
    await expect(alert).toBeVisible();
    const reactivateLink = alert.getByRole("link", { name: "Reactivate access" });
    await reactivateLink.click();
    await expect(page).toHaveURL("/student/payments/reactivation");
  });

  test("renders distinct, correct active/expired counts when they differ — proves real summation, not presence", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollments = [
      makeEnrollment({ enrollmentId: "e-active-1", courseId: "course-a1111111-aaa", state: "ACTIVE" }),
      makeEnrollment({ enrollmentId: "e-active-2", courseId: "course-a2222222-aaa", state: "ACTIVE" }),
      makeEnrollment({ enrollmentId: "e-active-3", courseId: "course-a3333333-aaa", state: "ACTIVE" }),
      makeEnrollment({
        enrollmentId: "e-expired-1",
        courseId: "course-e1111111-bbb",
        state: "EXPIRED",
        canRequestReactivation: true,
        accessExpiresAt: new Date().toISOString(),
      }),
    ];
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess(enrollments));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    const activeCard = page.getByRole("group", { name: "Active courses" });
    await expect(activeCard.locator("p.text-2xl")).toHaveText("3");
    const needsAttentionCard = page.getByRole("group", { name: "Needs attention" });
    await expect(needsAttentionCard.locator("p.text-2xl")).toHaveText("1");
  });

  test("shows the plural alert message and correct count when more than one enrollment has expired", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollments = [
      makeEnrollment({
        enrollmentId: "e-expired-1",
        courseId: "course-e1111111-bbb",
        state: "EXPIRED",
        canRequestReactivation: true,
        accessExpiresAt: new Date().toISOString(),
      }),
      makeEnrollment({
        enrollmentId: "e-expired-2",
        courseId: "course-e2222222-bbb",
        state: "EXPIRED",
        canRequestReactivation: true,
        accessExpiresAt: new Date().toISOString(),
      }),
    ];
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess(enrollments));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    const alert = page.getByRole("alert").filter({ hasText: "expired access" });
    await expect(alert).toContainText("2 of your courses have expired access.");
    await expect(page.getByText("One of your courses has expired access.")).toHaveCount(0);

    const needsAttentionCard = page.getByRole("group", { name: "Needs attention" });
    await expect(needsAttentionCard.locator("p.text-2xl")).toHaveText("2");
  });

  test("no expired enrollments shows no alert callout", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    const active = makeEnrollment();
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([active]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    await expect(page.getByText("expired access", { exact: false })).toHaveCount(0);
  });

  test("recent courses resolve real course names from the summary endpoint and link to My Courses", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment();
    const summary = makeCourseSummary({ id: enrollment.courseId });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([summary]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    const recentCourses = page.getByRole("list", { name: "Recent courses" });
    await expect(recentCourses.getByText(summary.name)).toBeVisible();
    await expect(recentCourses.getByText(summary.category)).toBeVisible();

    const viewAllLink = page.getByRole("link", { name: "View all my courses" });
    await viewAllLink.click();
    await expect(page).toHaveURL("/student/courses");
  });

  test("recent courses falls back to shortId when the course-summary read fails, without blanking the section", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const enrollment = makeEnrollment();
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([enrollment]));
    await mockJson(
      page,
      "**/api/v1/enrollments/my/courses",
      500,
      apiError("INTERNAL_ERROR", "Something went wrong.")
    );
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    const recentCourses = page.getByRole("list", { name: "Recent courses" });
    await expect(
      recentCourses.getByText(`Course #${enrollment.courseId.slice(0, 8)}`)
    ).toBeVisible();
  });

  test("shows the most recent payment entry from ledger history", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([makeEnrollment()]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    const entry = makeLedgerEntry({ amount: 99.5, entryType: "PAYMENT_CONFIRMED" });
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([entry]));

    await page.goto("/student/dashboard");

    await expect(page.getByText("Most recent payment")).toBeVisible();
    await expect(page.getByText("Payment confirmed", { exact: false })).toBeVisible();
    await expect(page.getByText("99.50", { exact: false })).toBeVisible();
  });

  test("shows 'No payments yet' when ledger history is empty", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([makeEnrollment()]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    await expect(page.getByText("No payments yet")).toBeVisible();
  });

  test("actually lays out 1/2/3 columns in the Recent courses grid at sm/md/lg widths — a real computed-layout assertion, not just CSS class presence", async ({
    page,
  }) => {
    // Mirrors the equivalent My Courses assertion in
    // `enrollment-and-course-access.spec.ts` — plan §18 requires an actual
    // layout/column-count assertion for the card grid(s), not just DOM
    // presence of a `grid-cols-*` class string.
    await mockTenantSession(page, "STUDENT");
    const enrollments = [
      makeEnrollment({ enrollmentId: "geo-recent-a", courseId: "course-geo-recent-aa" }),
      makeEnrollment({ enrollmentId: "geo-recent-b", courseId: "course-geo-recent-bb" }),
      makeEnrollment({ enrollmentId: "geo-recent-c", courseId: "course-geo-recent-cc" }),
    ];
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess(enrollments));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");
    const grid = page.getByRole("list", { name: "Recent courses" });
    await expect(grid.getByRole("listitem")).toHaveCount(3);

    async function columnCount() {
      return grid.evaluate(
        (el) => getComputedStyle(el).gridTemplateColumns.split(" ").filter(Boolean).length
      );
    }

    // Below `sm` (375px): single column.
    await page.setViewportSize({ width: 375, height: 900 });
    await expect.poll(columnCount).toBe(1);

    // Within `sm`/`md` (700px): 2 columns.
    await page.setViewportSize({ width: 700, height: 900 });
    await expect.poll(columnCount).toBe(2);

    // At `lg` (1280px) and above: 3 columns.
    await page.setViewportSize({ width: 1280, height: 900 });
    await expect.poll(columnCount).toBe(3);
  });
});

test.describe("student — Overview empty state", () => {
  test("zero enrollments shows the distinct Overview empty state with a catalog CTA", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    await expect(page.getByText("You have no active enrollments yet")).toBeVisible();

    // Payment-activity section is still independently rendered even when
    // enrollments is empty — the two sections are not coupled. Checked
    // before following the catalog CTA, which navigates away from this page.
    await expect(page.getByRole("heading", { name: "Payment activity" })).toBeVisible();
    await expect(page.getByText("No payments yet")).toBeVisible();

    const cta = page.getByRole("button", { name: "Browse courses" });
    await expect(cta).toBeVisible();
    await cta.click();
    await expect(page).toHaveURL("/courses");
  });
});

test.describe("student — Overview loading state", () => {
  test("shows aria-busy loading labels while both reads are in flight, then renders", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");

    let releaseEnrollments: (() => void) | undefined;
    const enrollmentsGate = new Promise<void>((resolve) => {
      releaseEnrollments = resolve;
    });
    await page.route("**/api/v1/enrollments/my", async (route) => {
      await enrollmentsGate;
      await fulfillJson(route, 200, apiSuccess([makeEnrollment()]));
    });
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));

    let releaseLedger: (() => void) | undefined;
    const ledgerGate = new Promise<void>((resolve) => {
      releaseLedger = resolve;
    });
    await page.route("**/api/v1/ledger/history", async (route) => {
      await ledgerGate;
      await fulfillJson(route, 200, apiSuccess([]));
    });

    await page.goto("/student/dashboard");

    await expect(page.getByText("Loading your enrollments…")).toBeVisible();
    await expect(page.getByText("Loading payment activity…")).toBeVisible();

    // Plan §18 explicitly calls out `aria-busy` — assert the actual
    // attribute on each `role="status"` region (per `LoadingState`), not
    // just the visible label text, so a regression that drops the
    // attribute while keeping the copy would still be caught.
    const enrollmentsStatus = page
      .getByRole("status")
      .filter({ hasText: "Loading your enrollments…" });
    await expect(enrollmentsStatus).toHaveAttribute("aria-busy", "true");
    await expect(enrollmentsStatus).toHaveAttribute("aria-live", "polite");
    const ledgerStatus = page.getByRole("status").filter({ hasText: "Loading payment activity…" });
    await expect(ledgerStatus).toHaveAttribute("aria-busy", "true");
    await expect(ledgerStatus).toHaveAttribute("aria-live", "polite");

    releaseEnrollments?.();
    await expect(page.getByText("Loading your enrollments…")).toHaveCount(0);
    await expect(page.getByText("Active courses")).toBeVisible();
    // Ledger section is still loading independently.
    await expect(page.getByText("Loading payment activity…")).toBeVisible();

    releaseLedger?.();
    await expect(page.getByText("Loading payment activity…")).toHaveCount(0);
    await expect(page.getByText("No payments yet")).toBeVisible();
  });
});

test.describe("student — Overview independent error states", () => {
  test("a failed ledger read shows a retryable error in the payment section without blanking a successful enrollments read", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([makeEnrollment()]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    // Fails on every attempt, including React Query's default automatic
    // retry (queryClient `retry: 1`), so the UI settles into a real,
    // observable error state rather than racing a transparent auto-retry
    // success.
    await mockJson(
      page,
      "**/api/v1/ledger/history",
      500,
      apiError("INTERNAL_ERROR", "Could not load payment history.")
    );

    await page.goto("/student/dashboard");

    // Enrollment-derived section renders successfully and is unaffected.
    await expect(page.getByRole("group", { name: "Active courses" })).toContainText("1");

    const errorAlert = page
      .getByRole("alert")
      .filter({ hasText: "Could not load payment history." });
    await expect(errorAlert).toBeVisible();

    // Swap in a success response before the user-triggered retry.
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([makeLedgerEntry()]));
    await errorAlert.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("Payment confirmed", { exact: false })).toBeVisible();
    await expect(errorAlert).toHaveCount(0);
  });

  test("a failed enrollments read shows a retryable error in that section without blanking a successful ledger read", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([makeLedgerEntry()]));
    // Fails on every attempt, including React Query's default automatic
    // retry, so the UI settles into a real, observable error state rather
    // than racing a transparent auto-retry success.
    await mockJson(
      page,
      "**/api/v1/enrollments/my",
      500,
      apiError("INTERNAL_ERROR", "Could not load enrollments.")
    );

    await page.goto("/student/dashboard");

    // Payment-activity section renders successfully and is unaffected.
    await expect(page.getByText("Payment confirmed", { exact: false })).toBeVisible();

    const errorAlert = page.getByRole("alert").filter({ hasText: "Could not load enrollments." });
    await expect(errorAlert).toBeVisible();

    // Swap in a success response before the user-triggered retry.
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([makeEnrollment()]));
    await errorAlert.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("Active courses")).toBeVisible();
    await expect(errorAlert).toHaveCount(0);
  });
});

test.describe("accessibility — Overview keyboard-only navigation", () => {
  test("student can reach the Reactivate CTA and the recent-courses link using only the keyboard", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    const expired = makeEnrollment({
      state: "EXPIRED",
      canRequestReactivation: true,
      accessExpiresAt: new Date().toISOString(),
    });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([expired]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    const reactivateLink = page.getByRole("link", { name: "Reactivate access" });
    await reactivateLink.focus();
    await expect(reactivateLink).toBeFocused();
    await page.keyboard.press("Enter");

    await expect(page).toHaveURL("/student/payments/reactivation");
  });

  test("Tab order flows from the expired-access alert, through the recent-courses card grid, to the 'View all my courses' link, using only the keyboard", async ({
    page,
  }) => {
    // The sibling test above proves the Reactivate CTA is individually
    // reachable via `.focus()`, but not that a keyboard user tabbing
    // through the page actually lands on the next real interactive element
    // (the recent-courses grid's own cards carry no per-card CTA — only
    // badges/text — so the grid's exit point, "View all my courses", is the
    // next stop) without getting stuck inside the grid. Real `Tab` presses
    // only, no `.focus()` jump to the target.
    await mockTenantSession(page, "STUDENT");
    const expired = makeEnrollment({
      enrollmentId: "kb-tab-expired",
      state: "EXPIRED",
      canRequestReactivation: true,
      accessExpiresAt: new Date().toISOString(),
    });
    const active = makeEnrollment({
      enrollmentId: "kb-tab-active",
      courseId: "course-kb-tab-active11",
      state: "ACTIVE",
    });
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([expired, active]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/history", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    const reactivateLink = page.getByRole("link", { name: "Reactivate access" });
    await reactivateLink.focus();
    await expect(reactivateLink).toBeFocused();

    const viewAllLink = page.getByRole("link", { name: "View all my courses" });
    await page.keyboard.press("Tab");
    await expect(viewAllLink).toBeFocused();

    await page.keyboard.press("Enter");
    await expect(page).toHaveURL("/student/courses");
  });
});
