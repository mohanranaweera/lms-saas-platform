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
    pricingModel: "ONE_TIME",
    archivedAt: null,
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

/**
 * R2 (G2/G3): the teacher edit page now uses the shared Klass
 * `CourseEditDetailsForm` (ids `edit-course-*`): one form (no steps), no slug
 * field (server-generated), and the teachers field is READ-ONLY for the
 * teacher portal. The form reads Klass fields, the linkable-course list and
 * tenant tags, so those are mocked too (an unmocked call would reach a running
 * backend with the fake token).
 */
function klassCourseFixture() {
  return {
    ...courseFixture(),
    teachers: [{ id: "teacher-1", name: "Teacher One", primary: true }],
    tags: [{ id: "tag-1", name: "Biology" }],
    linkedCourses: [] as { id: string; title: string }[],
    priceDisplay: { amount: 49.99, currency: "USD", period: null },
    enrollmentFee: "0.00",
    paymentValidDays: 180,
    commissionRate: null,
    whatsappInviteUrl: null,
    telegramInviteUrl: null,
    coverImageUrl: null,
    registrationEnabled: true,
    displayOrder: null,
    timezone: "Asia/Colombo",
  };
}

async function mockEditFormReads(page: Page) {
  await mockJson(page, "**/v1/course-tags", 200, apiSuccess([{ id: "tag-1", name: "Biology" }]));
  await page.route("**/api/v1/courses?*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(apiSuccess({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 })),
    });
  });
}

test.describe("teacher course edit — successful save", () => {
  test("loads the course pre-filled, teachers read-only, no slug; saves and sends no teacher/slug/status, preserving hidden legacy fields", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    const course = klassCourseFixture();
    let patchBody: unknown = null;
    await mockEditFormReads(page);
    await page.route(`**/api/v1/courses/${course.id}`, async (route) => {
      if (route.request().method() === "PATCH") {
        patchBody = route.request().postDataJSON();
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess({ ...course, name: "Intro to Biology (Updated)", description: "Updated." })),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(course)) });
    });

    await page.goto(`/teacher/courses/${course.id}/edit`);
    await expect(page.getByRole("heading", { name: "Edit course" })).toBeVisible();

    // Pre-filled from the fetched course; the slug is never shown or editable.
    await expect(page.getByTestId("edit-course-name")).toHaveValue("Intro to Biology");
    await expect(page.locator('[id*="slug"], [data-testid*="slug"]')).toHaveCount(0);
    await expect(page.getByText("intro-to-biology", { exact: true })).toHaveCount(0);

    // Teachers: read-only summary for a teacher; no picker, no primary radios.
    await expect(page.getByTestId("edit-course-teachers-readonly")).toHaveValue(/Teacher One/);
    await expect(page.getByTestId("edit-course-teachers")).toHaveCount(0);
    await expect(page.getByTestId("edit-course-primary-teacher")).toHaveCount(0);

    await page.getByTestId("edit-course-name").fill("Intro to Biology (Updated)");
    const advanced = page.getByTestId("edit-course-toggle-advanced");
    if ((await advanced.getAttribute("aria-expanded")) !== "true") await advanced.click();
    await page.getByTestId("edit-course-description").fill("Updated.");
    await page.getByTestId("edit-course-submit").click();

    await expect(page.getByText("Course details saved.")).toBeVisible();
    expect(patchBody).toMatchObject({
      name: "Intro to Biology (Updated)",
      description: "Updated.",
      // Not shown in the form: sent back unchanged so the PATCH never wipes them.
      subject: "Biology",
      stream: null,
      grade: "Grade 9",
      academicYear: "2026",
      enrollmentRule: "Open enrollment",
      paymentValidDays: 180,
    });
    // A teacher can never change the teacher list, and slug/status are never sent.
    expect(patchBody).not.toHaveProperty("teacherId");
    expect(patchBody).not.toHaveProperty("teacherIds");
    expect(patchBody).not.toHaveProperty("slug");
    expect(patchBody).not.toHaveProperty("status");
    // Unchanged fee is not re-sent.
    expect(patchBody).not.toHaveProperty("price");
  });
});

// Was "duplicate slug conflict" on edit: the slug is no longer user-editable
// (server-generated), so the kept intent is that a 409 on save is surfaced in
// the form, nothing claims success, and the edits are kept for a retry.
test.describe("teacher course edit — server conflict", () => {
  test("a 409 CONFLICT on save shows the form error, keeps the edits and never shows a saved confirmation", async ({
    page,
  }) => {
    await loginAsTeacher(page);
    const course = klassCourseFixture();
    const conflictMessage = "Another course in this institute already uses this name.";
    await mockEditFormReads(page);
    await page.route(`**/api/v1/courses/${course.id}`, async (route) => {
      if (route.request().method() === "PATCH") {
        await route.fulfill({
          status: 409,
          contentType: "application/json",
          body: JSON.stringify(apiError("CONFLICT", conflictMessage)),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(course)) });
    });

    await page.goto(`/teacher/courses/${course.id}/edit`);
    await page.getByTestId("edit-course-name").fill("Duplicate Name");
    await page.getByTestId("edit-course-submit").click();

    await expect(page.getByTestId("edit-course-form-error")).toContainText(conflictMessage);
    await expect(page.getByText("Course details saved.")).toHaveCount(0);
    await expect(page.getByTestId("edit-course-name")).toHaveValue("Duplicate Name");
    await expect(page.getByTestId("edit-course-submit")).toBeEnabled();
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
