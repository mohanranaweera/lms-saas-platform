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
 * Staff-facing course creation (PAR-05-02, Wave 2 — `tenant-admin/courses/new`,
 * `components/courses/course-create-form.tsx`'s `mode="staff"` variant). No
 * real backend runs in this environment (see `fixtures/auth-mocks.ts`).
 */

async function loginAsTenantAdmin(page: Page) {
  const token = fakeJwt({ role: "TENANT_ADMIN" });
  await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(token)));
  // Every test below reaches `/tenant-admin/courses/new` via a direct
  // `page.goto` (there is no in-app link exercised for most scenarios here),
  // which is a full browser navigation that drops the in-memory access token
  // (see `lib/auth/auth-context.tsx`) — mocking `/v1/auth/refresh` alongside
  // login lets the silent-refresh-on-mount flow re-establish a token instead
  // of hanging, mirroring `course-builder-keyboard.spec.ts`/
  // `teacher-course-edit.spec.ts`'s identical, already-established pattern.
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
  await mockJson(page, "**/v1/students", 200, apiSuccess([]));
  await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));
  await mockJson(page, "**/api/v1/ledger/dashboard*", 200, apiPageSuccess([]));
  await page.goto("/login");
  await page.getByLabel("Email").fill("admin@example.com");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/tenant-admin\/dashboard$/);
}

async function clickNext(page: Page) {
  await page.getByRole("button", { name: "Next", exact: true }).click();
}

const NEW_COURSE_ID = "d0000000-0000-0000-0000-000000000001";
const TEACHER_ID = "99999999-9999-9999-9999-999999999999";

function createdCourseFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: NEW_COURSE_ID,
    teacherId: TEACHER_ID,
    name: "Intro to Chemistry",
    slug: "intro-to-chemistry",
    category: "Science",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: null,
    price: 0,
    accessDurationDays: null,
    enrollmentRule: null,
    status: "DRAFT",
    pricingModel: "ONE_TIME",
    archivedAt: null,
    createdAt: "2026-03-01T00:00:00Z",
    updatedAt: "2026-03-01T00:00:00Z",
    ...overrides,
  };
}

async function fillThroughToTeacherStep(
  page: Page,
  options: { pricingModelOptionLabel?: string; price?: string } = {}
) {
  await page.locator("#course-create-name").fill("Intro to Chemistry");
  await page.locator("#course-create-slug").fill("intro-to-chemistry");
  await page.locator("#course-create-category").fill("Science");
  await clickNext(page); // Basics -> Classification

  await clickNext(page); // Classification -> Pricing

  if (options.pricingModelOptionLabel && options.pricingModelOptionLabel !== "One-time") {
    await page.locator("#course-create-pricingModel").click();
    await page.getByRole("option", { name: options.pricingModelOptionLabel }).click();
  }
  if (options.price !== undefined) {
    await page.locator("#course-create-price").fill(options.price);
  }
  await clickNext(page); // Pricing -> Enrollment & access

  await clickNext(page); // Enrollment & access -> Visibility

  await page.locator("#course-create-status").click();
  await page.getByRole("option", { name: "Draft" }).click();
  await clickNext(page); // Visibility -> Teacher
}

test.describe("tenant admin course create — staff variant", () => {
  test("has a Teacher ID step and the create form is reachable from the course list", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await page.getByRole("link", { name: "Courses" }).click();
    await page.getByRole("link", { name: "New course" }).first().click();

    await expect(page).toHaveURL(/\/tenant-admin\/courses\/new$/);
    await expect(page.getByRole("heading", { name: "Create a course" })).toBeVisible();
  });

  test("ONE_TIME: submits teacherId and price, and redirects to the new course's workspace", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await page.goto("/tenant-admin/courses/new");

    let postBody: unknown = null;
    await page.route("**/api/v1/courses", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      postBody = route.request().postDataJSON();
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(createdCourseFixture({ price: 49.99 }))),
      });
    });
    await mockJson(page, `**/v1/courses/${NEW_COURSE_ID}`, 200, apiSuccess(createdCourseFixture({ price: 49.99 })));

    await fillThroughToTeacherStep(page, { price: "49.99" });
    await page.locator("#course-create-teacherId").fill(TEACHER_ID);
    await page.getByRole("button", { name: "Create course" }).click();

    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${NEW_COURSE_ID}\\?created=1$`));
    await expect(page.getByText("Course created.")).toBeVisible();
    expect(postBody).toMatchObject({
      name: "Intro to Chemistry",
      slug: "intro-to-chemistry",
      category: "Science",
      price: 49.99,
      status: "DRAFT",
      teacherId: TEACHER_ID,
    });
  });

  test("FREE: creates the course (price 0), then composes a follow-up PATCH .../pricing-model before redirecting", async ({
    page,
  }) => {
    await loginAsTenantAdmin(page);
    await page.goto("/tenant-admin/courses/new");

    let postBody: unknown = null;
    await page.route("**/api/v1/courses", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      postBody = route.request().postDataJSON();
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(createdCourseFixture())),
      });
    });

    let pricingModelPatchBody: unknown = null;
    await page.route(`**/api/v1/courses/${NEW_COURSE_ID}/pricing-model`, async (route) => {
      pricingModelPatchBody = route.request().postDataJSON();
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(createdCourseFixture({ pricingModel: "FREE" }))),
      });
    });
    await mockJson(page, `**/v1/courses/${NEW_COURSE_ID}`, 200, apiSuccess(createdCourseFixture({ pricingModel: "FREE" })));

    await fillThroughToTeacherStep(page, { pricingModelOptionLabel: "Free" });
    // Price input must not even be rendered for a non-ONE_TIME model.
    await expect(page.locator("#course-create-price")).toHaveCount(0);
    await page.locator("#course-create-teacherId").fill(TEACHER_ID);
    await page.getByRole("button", { name: "Create course" }).click();

    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${NEW_COURSE_ID}\\?created=1$`));
    // `CourseCreateRequest` has no `pricingModel` field — `price` is forced
    // to 0 for a non-ONE_TIME model, and `pricingModel` is set via the
    // separate follow-up call below, never sent on the create request itself.
    expect(postBody).toMatchObject({ price: 0 });
    expect(postBody).not.toHaveProperty("pricingModel");
    expect(pricingModelPatchBody).toEqual({ pricingModel: "FREE" });
  });

  test("a failure on the follow-up pricing-model call surfaces an inline error with a link to the created course, without navigating away", async ({
    page,
  }) => {
    await loginAsTenantAdmin(page);
    await page.goto("/tenant-admin/courses/new");

    await page.route("**/api/v1/courses", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(createdCourseFixture())),
      });
    });
    await mockJson(
      page,
      `**/v1/courses/${NEW_COURSE_ID}/pricing-model`,
      500,
      apiError("INTERNAL_ERROR", "Could not update the pricing model right now.")
    );

    await fillThroughToTeacherStep(page, { pricingModelOptionLabel: "Monthly" });
    await page.locator("#course-create-teacherId").fill(TEACHER_ID);
    await page.getByRole("button", { name: "Create course" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "The course was created, but setting its pricing model failed" })
    ).toBeVisible();
    const goToCourseLink = page.getByRole("link", { name: "Go to the new course" });
    await expect(goToCourseLink).toBeVisible();
    await expect(goToCourseLink).toHaveAttribute("href", `/tenant-admin/courses/${NEW_COURSE_ID}/billing`);
    // Did not silently navigate away as if nothing went wrong.
    await expect(page).toHaveURL(/\/tenant-admin\/courses\/new$/);
  });

  test("a 403 on submit surfaces inline, without a full-page permission-denied takeover (course creation is a pure POST, no GET to fail up front)", async ({
    page,
  }) => {
    await loginAsTenantAdmin(page);
    await page.goto("/tenant-admin/courses/new");

    await mockJson(
      page,
      "**/api/v1/courses",
      403,
      apiError("FORBIDDEN", "You do not have permission to create a course.")
    );

    await fillThroughToTeacherStep(page, { price: "10" });
    await page.locator("#course-create-teacherId").fill(TEACHER_ID);
    await page.getByRole("button", { name: "Create course" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "You do not have permission to create a course." })
    ).toBeVisible();
    await expect(page).toHaveURL(/\/tenant-admin\/courses\/new$/);
  });
});
