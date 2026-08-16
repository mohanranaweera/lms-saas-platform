import { test, expect, type Page } from "@playwright/test";
import {
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
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
 * Unlike `tenant-admin-courses.spec.ts`, `TeacherNav` has no in-app "Courses"
 * link yet (see `components/layout/nav/teacher-nav.tsx` — only "Dashboard"
 * and a placeholder "Profile" item exist), so there is no client-side link to
 * navigate through without a full browser navigation. `**\/v1/auth/refresh` is
 * therefore also mocked (mirroring `course-modules.spec.ts`'s established
 * pattern for this same gap) so `page.goto("/teacher/courses")` after login
 * silently re-establishes the session from the refresh-token cookie instead
 * of losing the in-memory access token.
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
    await expect(page.getByRole("link", { name: "Create your first course" })).toBeVisible();

    // Must not reuse the Tenant Admin course-list empty-state copy.
    await expect(page.getByText("No courses in this tenant yet")).toHaveCount(0);
  });
});

test.describe("teacher course list — content and actions", () => {
  test("renders every assigned course with name, category, status, and price", async ({ page }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const table = page.getByRole("table");
    await expect(table).toBeVisible();

    await expect(table.getByText("Intro to Biology")).toBeVisible();
    await expect(table.getByText("Science")).toBeVisible();
    await expect(table.getByText("Public")).toBeVisible();
    await expect(table.getByText("49.99")).toBeVisible();

    await expect(table.getByText("Advanced Calculus")).toBeVisible();
    await expect(table.getByText("Mathematics")).toBeVisible();
    await expect(table.getByText("Draft")).toBeVisible();

    // Teacher's own list never needs the owning-teacher column (every row is
    // already this teacher's own course) — that column is Tenant-Admin-only.
    await expect(page.getByRole("columnheader", { name: "Teacher" })).toHaveCount(0);
  });

  test("each row's Edit and Modules actions link to the right routes", async ({ page }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    await expect(page.getByRole("table")).toBeVisible();

    const row = page.getByRole("row").filter({ hasText: "Intro to Biology" });
    await expect(row.getByRole("link", { name: "Edit" })).toHaveAttribute(
      "href",
      `/teacher/courses/${COURSES[0].id}/edit`
    );
    await expect(row.getByRole("link", { name: "Modules" })).toHaveAttribute(
      "href",
      `/teacher/courses/${COURSES[0].id}/modules`
    );
  });
});

test.describe("teacher course list — filters", () => {
  test("search narrows the list by name or slug, and a distinct filtered-empty state offers Clear filters", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const table = page.getByRole("table");
    await expect(table).toBeVisible();

    await page.getByLabel("Search").fill("biology");
    await expect(table.getByText("Intro to Biology")).toBeVisible();
    await expect(table.getByText("Advanced Calculus")).toHaveCount(0);

    await page.getByLabel("Search").fill("no-such-course");
    await expect(page.getByText("No courses match your filters")).toBeVisible();
    // Distinct from the true zero-data empty state.
    await expect(page.getByText("No assigned courses yet")).toHaveCount(0);

    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(table.getByText("Intro to Biology")).toBeVisible();
    await expect(table.getByText("Advanced Calculus")).toBeVisible();
  });

  test("category filter narrows the list", async ({ page }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const table = page.getByRole("table");
    await expect(table).toBeVisible();

    await page.getByLabel("Category").click();
    await page.getByRole("option", { name: "Mathematics" }).click();

    await expect(table.getByText("Advanced Calculus")).toBeVisible();
    await expect(table.getByText("Intro to Biology")).toHaveCount(0);
  });

  test("status filter narrows the list", async ({ page }) => {
    await loginAsTeacher(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess(COURSES));

    await page.goto("/teacher/courses");
    const table = page.getByRole("table");
    await expect(table).toBeVisible();

    await page.getByLabel("Status").click();
    await page.getByRole("option", { name: "Draft" }).click();

    await expect(table.getByText("Advanced Calculus")).toBeVisible();
    await expect(table.getByText("Intro to Biology")).toHaveCount(0);
  });
});
