import { test, expect, type Page } from "@playwright/test";
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
 * Teacher "My Courses" (`/teacher/courses`) coverage. No real backend runs in
 * this environment (see `fixtures/auth-mocks.ts`) — every test logs in
 * through a mocked `POST /v1/auth/login` and intercepts `/api/v1/courses/**`
 * directly.
 *
 * `loginAsTeacher()` below signs in through the UI, which lands on
 * `/teacher/dashboard` first (a real data-fetching page — it also calls
 * `useCourses()`) before each test navigates on to `/teacher/courses`.
 * `**\/v1/auth/refresh` is mocked so a subsequent direct `page.goto` keeps the
 * session alive from the refresh-token cookie. NOTE: because
 * `/teacher/dashboard` is a real Next.js page requiring a cold Turbopack
 * compile the first time it's hit, running just this file (or this file plus
 * `teacher-dashboard.spec.ts`) at Playwright's default parallel worker count
 * can show spurious failures from the dev-server cold-compile race documented
 * in `playwright.config.ts` (around lines 20-25). Use `--workers=1` for a
 * trustworthy local signal on these two files; this is a known infra
 * tradeoff, not a real regression.
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
    updatedAt: "2026-01-03T00:00:00Z",
  },
];

// Redirects through `/teacher/dashboard` (a real, data-fetching page) rather
// than a static placeholder — see this file's module doc comment above for
// why that means default-worker-count local runs of just these two teacher
// spec files can show spurious failures from the known Turbopack cold-compile
// race (`playwright.config.ts`, around lines 20-25). Not a real regression.
async function loginAsTeacher(page: Page) {
  const token = fakeJwt({ role: "TEACHER" });
  await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(token)));
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
  await page.goto("/login");
  await page.getByLabel("Email").fill("teacher@example.com");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/teacher\/dashboard$/);
}

test.describe("teacher course list — empty state", () => {
  test("shows teacher-specific empty copy, distinct from the tenant admin empty state", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));

    await page.goto("/teacher/courses");
    await expect(page.getByRole("heading", { name: "My Courses" })).toBeVisible();

    await expect(page.getByText("No assigned courses yet")).toBeVisible();
    await expect(
      page.getByText("contact your tenant admin if you expected to see courses assigned here already", {
        exact: false,
      })
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Create your first course" })).toBeVisible();

    // Must not reuse the Tenant Admin course-list empty-state copy.
    await expect(page.getByText("No courses in this tenant yet")).toHaveCount(0);
    // Must not reuse the Student "no active enrollments" empty-state copy
    // either — the issue's own explicit AC (`.claude/rules/ui-ux.md` §3),
    // since both are plausible "empty course list" states a shared/generic
    // `<EmptyState />` could otherwise collapse into the same string.
    await expect(page.getByText("You have no active enrollments yet")).toHaveCount(0);
  });
});

test.describe("teacher course list — content and actions", () => {
  test("renders every assigned course as a card with name, category, status, and price", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const grid = page.getByRole("list", { name: "My courses" });
    await expect(grid).toBeVisible();

    await expect(grid.getByText("Intro to Biology")).toBeVisible();
    await expect(grid.getByText("Science")).toBeVisible();
    await expect(grid.getByText("Public")).toBeVisible();
    await expect(grid.getByText("49.99")).toBeVisible();

    await expect(grid.getByText("Advanced Calculus")).toBeVisible();
    await expect(grid.getByText("Mathematics")).toBeVisible();
    await expect(grid.getByText("Draft")).toBeVisible();

    // Teacher's own list never needs an owning-teacher identifier (every
    // card is already this teacher's own course) — that's Tenant-Admin-only.
    await expect(grid.getByText(COURSES[0].teacherId)).toHaveCount(0);
  });

  test("each card's Edit and Modules actions link to the right routes", async ({ page }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const grid = page.getByRole("list", { name: "My courses" });
    await expect(grid).toBeVisible();

    const card = grid.getByRole("listitem").filter({ hasText: "Intro to Biology" });
    await expect(card.getByRole("link", { name: "Edit" })).toHaveAttribute(
      "href",
      `/teacher/courses/${COURSES[0].id}/edit`
    );
    await expect(card.getByRole("link", { name: "Modules" })).toHaveAttribute(
      "href",
      `/teacher/courses/${COURSES[0].id}/modules`
    );
  });

  test("lays out 1/2/3 columns in the course card grid at base/sm/lg widths", async ({ page }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const grid = page.getByRole("list", { name: "My courses" });
    await expect(grid.getByRole("listitem")).toHaveCount(2);

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

test.describe("teacher course list — filters", () => {
  test("search narrows the list by name or slug, and a distinct filtered-empty state offers Clear filters", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const grid = page.getByRole("list", { name: "My courses" });
    await expect(grid).toBeVisible();

    await page.getByLabel("Search").fill("biology");
    await expect(grid.getByText("Intro to Biology")).toBeVisible();
    await expect(grid.getByText("Advanced Calculus")).toHaveCount(0);

    await page.getByLabel("Search").fill("no-such-course");
    await expect(page.getByText("No courses match your filters")).toBeVisible();
    // Distinct from the true zero-data empty state.
    await expect(page.getByText("No assigned courses yet")).toHaveCount(0);

    await page.getByRole("button", { name: "Clear filters" }).click();
    const gridAfterClear = page.getByRole("list", { name: "My courses" });
    await expect(gridAfterClear.getByText("Intro to Biology")).toBeVisible();
    await expect(gridAfterClear.getByText("Advanced Calculus")).toBeVisible();
  });

  test("category filter narrows the list", async ({ page }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const grid = page.getByRole("list", { name: "My courses" });
    await expect(grid).toBeVisible();

    await page.getByLabel("Category").click();
    await page.getByRole("option", { name: "Mathematics" }).click();

    await expect(grid.getByText("Advanced Calculus")).toBeVisible();
    await expect(grid.getByText("Intro to Biology")).toHaveCount(0);
  });

  test("status filter narrows the list", async ({ page }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const grid = page.getByRole("list", { name: "My courses" });
    await expect(grid).toBeVisible();

    await page.getByLabel("Status").click();
    await page.getByRole("option", { name: "Draft" }).click();

    await expect(grid.getByText("Advanced Calculus")).toBeVisible();
    await expect(grid.getByText("Intro to Biology")).toHaveCount(0);
  });
});

test.describe("teacher course list — keyboard-only navigation", () => {
  test("Tab traversal reaches every card's Edit and Modules actions, in DOM order, across multiple cards", async ({
    page,
  }) => {
    // Mirrors `teacher-dashboard.spec.ts`'s "Tab order flows..." test's own
    // documented rigor: real sequential `Tab` presses only, starting from
    // page load, no `.focus()` jump to a target element. This proves a
    // keyboard-only user can actually *reach* the first card's Edit link
    // (not just that the relative order among links, once reached, is
    // correct), bounded so a regression that removes a link (or traps focus)
    // fails loudly instead of hanging.
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const grid = page.getByRole("list", { name: "My courses" });
    await expect(grid).toBeVisible();

    const firstCard = grid.getByRole("listitem").filter({ hasText: "Intro to Biology" });
    const firstEdit = firstCard.getByRole("link", { name: "Edit" });

    for (let i = 0; i < 30; i += 1) {
      await page.keyboard.press("Tab");
      if (await firstEdit.evaluate((el) => el === document.activeElement)) {
        break;
      }
    }
    await expect(firstEdit).toBeFocused();

    await page.keyboard.press("Tab");
    await expect(firstCard.getByRole("link", { name: "Modules" })).toBeFocused();

    const secondCard = grid.getByRole("listitem").filter({ hasText: "Advanced Calculus" });
    await page.keyboard.press("Tab");
    await expect(secondCard.getByRole("link", { name: "Edit" })).toBeFocused();

    await page.keyboard.press("Tab");
    const secondModules = secondCard.getByRole("link", { name: "Modules" });
    await expect(secondModules).toBeFocused();
    await expect(secondModules).toHaveAttribute("href", `/teacher/courses/${COURSES[1].id}/modules`);
  });
});

test.describe("teacher course list — loading state", () => {
  test("shows an aria-busy loading label while the read is in flight, then renders", async ({
    page,
  }) => {
    await loginAsTeacher(page);

    let releaseCourses: (() => void) | undefined;
    const coursesGate = new Promise<void>((resolve) => {
      releaseCourses = resolve;
    });
    await page.route("**/v1/courses*", async (route) => {
      await coursesGate;
      await fulfillJson(route, 200, apiPageSuccess(COURSES));
    });

    await page.goto("/teacher/courses");

    const status = page.getByRole("status").filter({ hasText: "Loading your courses…" });
    await expect(status).toBeVisible();
    await expect(status).toHaveAttribute("aria-busy", "true");
    await expect(status).toHaveAttribute("aria-live", "polite");

    releaseCourses?.();
    await expect(page.getByText("Loading your courses…")).toHaveCount(0);
    await expect(page.getByRole("list", { name: "My courses" })).toBeVisible();
  });
});

test.describe("teacher course list — error state", () => {
  test("a failed read shows a retryable error, and Retry recovers", async ({ page }) => {
    await loginAsTeacher(page);
    // Fails on every attempt, including React Query's default automatic
    // retry, so the UI settles into a real, observable error state rather
    // than racing a transparent auto-retry success.
    await mockJson(
      page,
      "**/v1/courses*",
      500,
      apiError("INTERNAL_ERROR", "Could not load your courses.")
    );

    await page.goto("/teacher/courses");

    const errorAlert = page.getByRole("alert").filter({ hasText: "Could not load your courses." });
    await expect(errorAlert).toBeVisible();

    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));
    await errorAlert.getByRole("button", { name: "Try again" }).click();

    await expect(page.getByRole("list", { name: "My courses" })).toBeVisible();
    await expect(errorAlert).toHaveCount(0);
  });
});

test.describe("teacher course list — permission-denied state", () => {
  test("a 403 from GET /api/v1/courses renders PermissionDeniedState, not a raw error", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    await mockJson(
      page,
      "**/v1/courses*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view these courses.")
    );

    await page.goto("/teacher/courses");

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
  test("an unauthenticated visit to /teacher/courses redirects to /login before portal chrome renders", async ({
    page,
  }) => {
    // No session mocked at all: `POST /v1/auth/refresh` fails, so
    // `RouteGuard` redirects instead of resolving `ready`. Mirrors
    // `teacher-dashboard.spec.ts`'s equivalent "teacher portal — RouteGuard"
    // test exactly, for `/teacher/courses`.
    await mockJson(page, "**/v1/auth/refresh", 401, apiError("UNAUTHENTICATED", "No session."));

    await page.goto("/teacher/courses");

    await expect(page).toHaveURL(/\/login(\?|$)/);
    await expect(page.getByText("Teacher Portal")).toHaveCount(0);
  });
});
