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
 * MVP-014 Teacher Dashboard — Overview (`/teacher/dashboard`, TDASH-1).
 *
 * A single, already teacher-ownership-scoped read (`GET /api/v1/courses`,
 * the same `useCourses()` call `/teacher/courses` already makes) composed
 * client-side into stat cards and a "recent courses" list — no new backend
 * call. No real backend runs in this environment (see
 * `fixtures/auth-mocks.ts`'s module doc) — every test mocks `/v1/**`
 * responses shaped like the documented `ApiResponse<T>` envelope.
 *
 * `(teacher)/layout.tsx` is now wrapped in `RouteGuard` (`kind="tenant"`),
 * which calls `ensureAccessToken("tenant")` on mount. Since these tests
 * navigate directly to a `/teacher/**` URL rather than logging in through
 * the UI first, every test mocks a successful `POST /v1/auth/refresh` so the
 * guard resolves, mirroring `teacher-management.spec.ts`'s established
 * pattern for the same gap.
 */

const COURSES = [
  {
    id: "11111111-1111-1111-1111-111111111111",
    teacherId: "teacher-1",
    name: "Intro to Biology",
    slug: "intro-to-biology",
    category: "Science",
    subject: "Biology",
    stream: null,
    grade: "Grade 9",
    academicYear: "2026",
    description: "A beginner biology course.",
    price: 49.99,
    accessDurationDays: 180,
    enrollmentRule: "Open enrollment",
    status: "PUBLIC",
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-02T00:00:00Z",
  },
  {
    id: "22222222-2222-2222-2222-222222222222",
    teacherId: "teacher-1",
    name: "Advanced Calculus",
    slug: "advanced-calculus",
    category: "Mathematics",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: null,
    price: 99.5,
    accessDurationDays: null,
    enrollmentRule: null,
    status: "DRAFT",
    createdAt: "2026-01-03T00:00:00Z",
    updatedAt: "2026-01-05T00:00:00Z",
  },
  {
    id: "33333333-3333-3333-3333-333333333333",
    teacherId: "teacher-1",
    name: "Organic Chemistry",
    slug: "organic-chemistry",
    category: "Science",
    subject: "Chemistry",
    stream: null,
    grade: "Grade 11",
    academicYear: "2026",
    description: null,
    price: 79.0,
    accessDurationDays: 365,
    enrollmentRule: null,
    status: "PRIVATE",
    createdAt: "2026-01-02T00:00:00Z",
    updatedAt: "2026-01-04T00:00:00Z",
  },
];

/** Establishes a session for the given role by mocking `POST /v1/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

test.describe("teacher overview — populated state", () => {
  test("renders correct assigned/published/draft counts computed from the fetched courses", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/dashboard");

    const assignedCard = page.getByRole("group", { name: "Assigned courses" });
    await expect(assignedCard).toContainText("3");
    const publishedCard = page.getByRole("group", { name: "Published" });
    await expect(publishedCard).toContainText("1");
    const draftCard = page.getByRole("group", { name: "Draft / private" });
    await expect(draftCard).toContainText("2");
  });

  test("recent courses are sorted most-recently-updated first and link to My Courses", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/dashboard");

    const recentCourses = page.getByRole("list", { name: "Recent courses" });
    const items = recentCourses.getByRole("listitem");
    await expect(items).toHaveCount(3);
    // Advanced Calculus (updatedAt 01-05) is the most recently updated,
    // Intro to Biology (01-02) the least recently updated of the three.
    await expect(items.nth(0)).toContainText("Advanced Calculus");
    await expect(items.nth(2)).toContainText("Intro to Biology");

    const viewAllLink = page.getByRole("link", { name: "View all my courses" });
    await viewAllLink.click();
    await expect(page).toHaveURL("/teacher/courses");
  });

  test("actually lays out 1/2/3 columns in the Recent courses grid at base/sm/lg widths", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/dashboard");
    const grid = page.getByRole("list", { name: "Recent courses" });
    await expect(grid.getByRole("listitem")).toHaveCount(3);

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
});

test.describe("teacher overview — keyboard-only navigation", () => {
  test("Tab order flows from the stat cards, through the Recent courses grid, to the 'View all my courses' link, using only the keyboard", async ({
    page,
  }) => {
    // Mirrors `student-dashboard.spec.ts`'s "Tab order flows from the
    // expired-access alert, through the recent-courses card grid, to the
    // 'View all my courses' link" test — the Recent courses cards here
    // carry no per-card CTA (only badges/text), so the grid's exit point,
    // "View all my courses", is the next real Tab stop. Real `Tab` presses
    // only, no `.focus()` jump to the target.
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/dashboard");

    const assignedCard = page.getByRole("group", { name: "Assigned courses" });
    await expect(assignedCard).toBeVisible();

    const viewAllLink = page.getByRole("link", { name: "View all my courses" });
    // The stat cards and each Recent-courses `<li>` are presentational (no
    // interactive content), so there is no single, stable "card before the
    // link" anchor to `.focus()` directly, unlike the Edit/Modules-per-card
    // case on My Courses. Instead, drive real `Tab` presses from the top of
    // the page and assert the link is reached — proving focus is never
    // trapped inside the non-interactive grid — bounded so a regression that
    // removes the link (or breaks Tab order) fails loudly instead of
    // hanging.
    for (let i = 0; i < 20; i += 1) {
      await page.keyboard.press("Tab");
      if (await viewAllLink.evaluate((el) => el === document.activeElement)) {
        break;
      }
    }
    await expect(viewAllLink).toBeFocused();

    await page.keyboard.press("Enter");
    await expect(page).toHaveURL("/teacher/courses");
  });
});

test.describe("teacher overview — empty state", () => {
  test("zero assigned courses shows the same 'No assigned courses yet' copy as My Courses", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));

    await page.goto("/teacher/dashboard");

    await expect(page.getByText("No assigned courses yet")).toBeVisible();
    await expect(
      page.getByText("contact your tenant admin if you expected to see courses assigned here already", {
        exact: false,
      })
    ).toBeVisible();

    const cta = page.getByRole("button", { name: "Create your first course" });
    await expect(cta).toBeVisible();
    await cta.click();
    await expect(page).toHaveURL("/teacher/courses/new");
  });
});

test.describe("teacher overview — loading state", () => {
  test("shows an aria-busy loading label while the read is in flight, then renders", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");

    let releaseCourses: (() => void) | undefined;
    const coursesGate = new Promise<void>((resolve) => {
      releaseCourses = resolve;
    });
    await page.route("**/v1/courses*", async (route) => {
      await coursesGate;
      await fulfillJson(route, 200, apiPageSuccess(COURSES));
    });

    await page.goto("/teacher/dashboard");

    const status = page.getByRole("status").filter({ hasText: "Loading your courses…" });
    await expect(status).toBeVisible();
    await expect(status).toHaveAttribute("aria-busy", "true");
    await expect(status).toHaveAttribute("aria-live", "polite");

    releaseCourses?.();
    await expect(page.getByText("Loading your courses…")).toHaveCount(0);
    await expect(page.getByRole("group", { name: "Assigned courses" })).toBeVisible();
  });
});

test.describe("teacher overview — error state", () => {
  test("a failed read shows a retryable error, and Retry recovers", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    // Fails on every attempt, including React Query's default automatic
    // retry, so the UI settles into a real, observable error state rather
    // than racing a transparent auto-retry success.
    await mockJson(
      page,
      "**/v1/courses*",
      500,
      apiError("INTERNAL_ERROR", "Could not load your courses.")
    );

    await page.goto("/teacher/dashboard");

    const errorAlert = page.getByRole("alert").filter({ hasText: "Could not load your courses." });
    await expect(errorAlert).toBeVisible();

    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await errorAlert.getByRole("button", { name: "Try again" }).click();

    await expect(page.getByRole("group", { name: "Assigned courses" })).toBeVisible();
    await expect(errorAlert).toHaveCount(0);
  });
});

test.describe("teacher overview — permission-denied state", () => {
  test("a 403 from GET /api/v1/courses renders PermissionDeniedState, not a raw error", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(
      page,
      "**/v1/courses*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view these courses.")
    );

    await page.goto("/teacher/dashboard");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied).toContainText("You do not have permission to view these courses.");
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/teacher/dashboard"
    );
  });
});

test.describe("teacher portal — RouteGuard", () => {
  test("an unauthenticated visit to /teacher/dashboard redirects to /login before portal chrome renders", async ({
    page,
  }) => {
    // No session mocked at all: `POST /v1/auth/refresh` fails, so
    // `RouteGuard` redirects instead of resolving `ready`.
    await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session."));

    await page.goto("/teacher/dashboard");

    await expect(page).toHaveURL(/\/login(\?|$)/);
    await expect(page.getByText("Teacher Portal")).toHaveCount(0);
  });
});
