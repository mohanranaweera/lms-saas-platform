import { test, expect, type Page, type Route } from "@playwright/test";
import {
  apiError,
  apiSuccess,
  fakeJwt,
  loginResponseBody,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Teacher Course Builder — **edit** flow (`/teacher/courses/[courseId]/edit`,
 * `components/courses/course-edit-form.tsx`) plus the duplicate-slug
 * conflict path on the **create** flow (`components/courses/
 * course-create-form.tsx`) and the "no `teacherId` field anywhere" assertion
 * for both. Closes the Blocking gap noted in the MVP-008 frontend review:
 * before this file, no spec anywhere navigated to the edit route or clicked
 * its "Edit" link.
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`) —
 * every test logs in through a mocked `POST /v1/auth/login` and mocks
 * `**\/v1/auth/refresh` alongside it (mirroring `course-modules.spec.ts`'s
 * established pattern) so a direct `page.goto` to the edit/new route after
 * login doesn't lose the in-memory access token.
 *
 * `course-edit-form.tsx`'s `onSubmit` (read directly, not guessed): a
 * `409`/any-status `ApiClientError` with a non-empty `fieldErrors` array maps
 * each known field name to `setError` and jumps to the *earliest* affected
 * step (the `error.code === "CONFLICT"` branch below it is a fallback that
 * only fires when `fieldErrors` is empty — it unconditionally targets the
 * `slug` field and the Basics step regardless of the error message).
 * `course-create-form.tsx` mirrors this exactly. Both branches are exercised
 * below: the edit test uses the empty-`fieldErrors` `CONFLICT` fallback path,
 * the create test uses the `fieldErrors`-bearing path — proving both actually
 * converge on the same visible outcome (inline slug error, Basics step).
 */

const COURSE_ID = "c0000000-0000-0000-0000-000000000001";

function courseFixture() {
  return {
    id: COURSE_ID,
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
  };
}

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

function currentStepIndicator(page: Page) {
  return page.locator('[aria-current="step"]');
}

async function clickNext(page: Page) {
  await page.getByRole("button", { name: "Next", exact: true }).click();
}

/**
 * Registers a single stateful route for the exact `/api/v1/courses/{id}`
 * resource (never a broader glob — this must not also catch
 * `/api/v1/courses/{id}/price` or `/api/v1/courses/{id}/publish`, which
 * `CourseVisibilityControl`/`CoursePriceChangeForm` on the same page could
 * otherwise call). GET always returns the current fixture; PATCH is
 * dispatched to `onPatch`, which decides the response and can inspect the
 * captured request body.
 */
async function mockCourseDetailRoute(
  page: Page,
  course: ReturnType<typeof courseFixture>,
  onPatch: (route: Route, body: unknown) => Promise<void>
) {
  await page.route(`**/api/v1/courses/${course.id}`, async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(course)),
      });
      return;
    }
    if (route.request().method() === "PATCH") {
      await onPatch(route, route.request().postDataJSON());
      return;
    }
    await route.fallback();
  });
}

test.describe("teacher course edit — successful save", () => {
  test("loads the course pre-filled, saves across all three steps, shows the confirmation, and sends no teacherId/price/status", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    const course = courseFixture();
    let patchBody: unknown = null;

    await mockCourseDetailRoute(page, course, async (route, body) => {
      patchBody = body;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ ...course, name: "Intro to Biology (Updated)" })),
      });
    });

    await page.goto(`/teacher/courses/${course.id}/edit`);
    await expect(page.getByRole("heading", { name: "Edit course" })).toBeVisible();

    // Fields are pre-filled from the fetched course.
    await expect(page.locator("#course-edit-name")).toHaveValue("Intro to Biology");
    await expect(page.locator("#course-edit-slug")).toHaveValue("intro-to-biology");

    // No teacher-related control anywhere on this form.
    await expect(page.getByLabel(/teacher/i)).toHaveCount(0);

    // --- Step 1: Basics ---
    await page.locator("#course-edit-name").fill("Intro to Biology (Updated)");
    await clickNext(page);

    // --- Step 2: Classification ---
    await expect(currentStepIndicator(page)).toContainText("Classification");
    await page.locator("#course-edit-subject").fill("Life Sciences");
    await clickNext(page);

    // --- Step 3: Enrollment & access ---
    await expect(currentStepIndicator(page)).toContainText("Enrollment & access");
    await page.locator("#course-edit-enrollmentRule").fill("Open enrollment - waitlist available");

    await page.getByRole("button", { name: "Save changes" }).click();

    await expect(page.getByRole("status").filter({ hasText: "Course details saved." })).toBeVisible();

    expect(patchBody).toMatchObject({
      name: "Intro to Biology (Updated)",
      slug: "intro-to-biology",
      category: "Science",
      subject: "Life Sciences",
      grade: "Grade 9",
      academicYear: "2026",
      description: "A beginner biology course.",
      enrollmentRule: "Open enrollment - waitlist available",
      accessDurationDays: 180,
    });
    // `CourseUpdateRequest` structurally excludes these — prove it holds at
    // the network level too, not just in the type.
    expect(patchBody).not.toHaveProperty("teacherId");
    expect(patchBody).not.toHaveProperty("price");
    expect(patchBody).not.toHaveProperty("status");
  });
});

test.describe("teacher course edit — duplicate slug conflict", () => {
  test("a 409 CONFLICT (no fieldErrors) on save returns to the Basics step and shows an inline slug error", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    const course = courseFixture();
    const conflictMessage = "This slug is already registered for another course in your tenant.";

    await mockCourseDetailRoute(page, course, async (route) => {
      await route.fulfill({
        status: 409,
        contentType: "application/json",
        body: JSON.stringify(apiError("CONFLICT", conflictMessage)),
      });
    });

    await page.goto(`/teacher/courses/${course.id}/edit`);
    await expect(page.getByRole("heading", { name: "Edit course" })).toBeVisible();

    await clickNext(page); // Basics -> Classification
    await clickNext(page); // Classification -> Enrollment & access
    await expect(currentStepIndicator(page)).toContainText("Enrollment & access");

    await page.getByRole("button", { name: "Save changes" }).click();

    // Jumped back to the Basics step, where `slug` lives.
    await expect(currentStepIndicator(page)).toContainText("Basics");
    await expect(page.getByRole("heading", { name: "Basics", exact: true })).toBeFocused();

    const slugInput = page.locator("#course-edit-slug");
    await expect(slugInput).toHaveAttribute("aria-invalid", "true");
    const slugError = page.locator("#course-edit-slug-error");
    await expect(slugError).toBeVisible();
    await expect(slugError).toHaveText(conflictMessage);

    // Exactly one alert within the app content — the inline field error —
    // never the generic page-level ErrorState (the CONFLICT branch returns
    // early without setting `pageError`). Scoped to `main` because the
    // Next.js dev-mode issues overlay also renders its own unrelated
    // `role="alert"` badge outside the app content in this environment.
    await expect(page.getByRole("main").getByRole("alert")).toHaveCount(1);
    await expect(page.getByText("Course details saved.")).toHaveCount(0);
  });
});

test.describe("teacher course create — duplicate slug conflict", () => {
  async function fillCreateThroughToVisibility(page: Page) {
    await page.locator("#course-create-name").fill("Intro to Biology");
    await page.locator("#course-create-slug").fill("intro-to-biology");
    await page.locator("#course-create-category").fill("Science");
    await clickNext(page); // Basics -> Classification

    await clickNext(page); // Classification -> Pricing

    await page.locator("#course-create-price").fill("49.99");
    await clickNext(page); // Pricing -> Enrollment & access

    await clickNext(page); // Enrollment & access -> Visibility

    await page.locator("#course-create-status").click();
    await page.getByRole("option", { name: "Public" }).click();
  }

  test("a 422 with a slug fieldError returns to the Basics step and shows an inline slug error", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    const fieldErrorMessage = "This slug is already in use for this tenant.";
    let postBody: unknown = null;

    await page.route("**/api/v1/courses", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      postBody = route.request().postDataJSON();
      await route.fulfill({
        status: 422,
        contentType: "application/json",
        body: JSON.stringify(
          apiError("VALIDATION_ERROR", "Validation failed.", [
            { field: "slug", message: fieldErrorMessage },
          ])
        ),
      });
    });

    await page.goto("/teacher/courses/new");
    await expect(page.getByRole("heading", { name: "Create a course" })).toBeVisible();
    await expect(page.getByLabel(/teacher/i)).toHaveCount(0);

    await fillCreateThroughToVisibility(page);

    await page.getByRole("button", { name: "Create course" }).click();

    await expect(currentStepIndicator(page)).toContainText("Basics");
    await expect(page.getByRole("heading", { name: "Basics", exact: true })).toBeFocused();

    const slugInput = page.locator("#course-create-slug");
    await expect(slugInput).toHaveAttribute("aria-invalid", "true");
    const slugError = page.locator("#course-create-slug-error");
    await expect(slugError).toBeVisible();
    await expect(slugError).toHaveText(fieldErrorMessage);

    await expect(page.getByRole("main").getByRole("alert")).toHaveCount(1);
    await expect(page).toHaveURL(/\/teacher\/courses\/new$/);

    // The rejected request never carried a teacherId, even though it did
    // carry price/status (which the create contract legitimately includes).
    expect(postBody).not.toHaveProperty("teacherId");
  });
});

test.describe("teacher course edit — permission denied", () => {
  test("a 403 on the course fetch renders the Teacher permission-denied state with a link back to the dashboard", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    const course = courseFixture();
    const forbiddenMessage = "You do not have access to this course.";

    await mockJson(
      page,
      `**/api/v1/courses/${course.id}`,
      403,
      apiError("FORBIDDEN", forbiddenMessage)
    );

    await page.goto(`/teacher/courses/${course.id}/edit`);

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByText(forbiddenMessage)).toBeVisible();
    const dashboardLink = page.getByRole("link", { name: "Back to your dashboard" });
    await expect(dashboardLink).toBeVisible();
    await expect(dashboardLink).toHaveAttribute("href", "/teacher/dashboard");

    // The permission-denied state renders in place of the form entirely.
    await expect(page.locator("#course-edit-name")).toHaveCount(0);
  });
});
