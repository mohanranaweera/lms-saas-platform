import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Student "Course Content" (`app/(student)/student/courses/[courseId]/page.tsx`)
 * and the "Open course" entry point on "My courses".
 *
 * An enrolled Student with an ACTIVE enrollment (inside the paid access
 * window) sees the course's modules and lessons, each lesson linking to the
 * existing lesson materials page. EXPIRED / never-enrolled rows never call
 * the course endpoint, and a backend 403/404 on the course read renders fixed
 * local copy — never the backend's message text.
 *
 * Mock-only (no real backend): the server-side enrollment check itself is
 * proven by `EnrolledStudentCourseAccessIntegrationTest`, including the
 * cross-tenant and never-enrolled denials.
 */

const COURSE_ID = "c0000000-0000-0000-0000-00000000c0de";
const MODULE_ID = "module-1";
const LESSON_ID = "lesson-1";

async function mockStudentSession(page: Page): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
}

function makeEnrollment(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    enrollmentId: "enrollment-1",
    courseId: COURSE_ID,
    state: "ACTIVE" as const,
    accessExpiresAt: "2026-10-24T18:12:04Z",
    canRequestReactivation: false,
    ...overrides,
  };
}

function makeCourse() {
  return {
    id: COURSE_ID,
    teacherId: "teacher-1",
    name: "2028 Biology Theory Class",
    slug: "biology-2028",
    category: "Biology",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: "Monthly theory class.",
    price: 4500,
    accessDurationDays: 30,
    enrollmentRule: null,
    status: "PRIVATE",
    pricingModel: "MONTHLY",
    archivedAt: null,
    createdAt: "2026-09-01T00:00:00Z",
    updatedAt: "2026-09-01T00:00:00Z",
    resolvedAmount: 4500,
    currency: "LKR",
    requiresManualQuote: false,
  };
}

async function mockCourseContent(page: Page): Promise<{ courseCalls: () => number }> {
  let calls = 0;
  await page.route(`**/api/v1/courses/${COURSE_ID}`, async (route) => {
    calls += 1;
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(makeCourse())) });
  });
  await mockJson(page, `**/api/v1/courses/${COURSE_ID}/modules`, 200,
    apiSuccess([{ id: MODULE_ID, courseId: COURSE_ID, title: "Online Live class", sequence: 1, createdAt: "", updatedAt: "" }]));
  await mockJson(page, `**/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons`, 200,
    apiSuccess([{ id: LESSON_ID, moduleId: MODULE_ID, title: "Lesson 01", sequence: 1, createdAt: "", updatedAt: "" }]));
  return { courseCalls: () => calls };
}

test.describe("student — My courses entry point", () => {
  test("an ACTIVE enrollment's card links to the course content page", async ({ page }) => {
    await mockStudentSession(page);
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([makeEnrollment()]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200,
      apiSuccess([{ id: COURSE_ID, name: "2028 Biology Theory Class", slug: "biology-2028", category: "Biology" }]));
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));

    await page.goto("/student/courses");

    const open = page.getByRole("link", { name: "Open course 2028 Biology Theory Class" });
    await expect(open).toHaveAttribute("href", `/student/courses/${COURSE_ID}`);
  });

  test("an EXPIRED enrollment's card offers no Open course link", async ({ page }) => {
    await mockStudentSession(page);
    await mockJson(page, "**/api/v1/enrollments/my", 200,
      apiSuccess([makeEnrollment({ state: "EXPIRED", canRequestReactivation: true })]));
    await mockJson(page, "**/api/v1/enrollments/my/courses", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/reactivation-requests/my*", 200, apiPageSuccess([]));

    await page.goto("/student/courses");

    await expect(page.getByRole("link", { name: /Reactivate/ })).toBeVisible();
    await expect(page.getByRole("link", { name: /^Open course/ })).toHaveCount(0);
  });
});

test.describe("student — course content page", () => {
  test("an ACTIVE enrollment shows the course, its access window, modules, and lesson links", async ({ page }) => {
    await mockStudentSession(page);
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([makeEnrollment()]));
    await mockCourseContent(page);

    await page.goto(`/student/courses/${COURSE_ID}`);

    await expect(page.getByRole("heading", { name: "2028 Biology Theory Class" })).toBeVisible();
    await expect(page.getByText(/Access until/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Online Live class" })).toBeVisible();
    await expect(page.getByRole("link", { name: /Lesson 01/ })).toHaveAttribute(
      "href",
      `/student/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials`
    );
  });

  test("an EXPIRED enrollment shows the access-ended state and never requests the course", async ({ page }) => {
    await mockStudentSession(page);
    await mockJson(page, "**/api/v1/enrollments/my", 200,
      apiSuccess([makeEnrollment({ state: "EXPIRED", accessExpiresAt: "2026-09-01T00:00:00Z" })]));
    const content = await mockCourseContent(page);

    await page.goto(`/student/courses/${COURSE_ID}`);

    await expect(page.getByText("Your access to this course has ended")).toBeVisible();
    await expect(page.getByRole("button", { name: "Back to My courses" })).toBeVisible();
    expect(content.courseCalls()).toBe(0);
  });

  test("a course the Student is not enrolled in shows the not-available state and never requests it", async ({
    page,
  }) => {
    await mockStudentSession(page);
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([]));
    const content = await mockCourseContent(page);

    await page.goto(`/student/courses/${COURSE_ID}`);

    await expect(page.getByText("This course isn't available")).toBeVisible();
    expect(content.courseCalls()).toBe(0);
  });

  test("a backend 403 on the course read renders fixed copy, never the backend's message", async ({ page }) => {
    const backendMessage = "xyz-should-never-render-in-ui";
    await mockStudentSession(page);
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([makeEnrollment()]));
    await mockJson(page, `**/api/v1/courses/${COURSE_ID}`, 403, apiError("FORBIDDEN", backendMessage));
    await mockJson(page, `**/api/v1/courses/${COURSE_ID}/modules`, 403, apiError("FORBIDDEN", backendMessage));

    await page.goto(`/student/courses/${COURSE_ID}`);

    await expect(page.getByText("This course isn't open yet")).toBeVisible();
    await expect(page.getByText(backendMessage)).toHaveCount(0);
  });
});

test.describe("student — Browse courses (in-portal catalog)", () => {
  test("lists the institute's courses inside the portal: Enroll goes to checkout, an ACTIVE course offers Open course", async ({
    page,
  }) => {
    const OTHER_ID = "c0000000-0000-0000-0000-0000000000aa";
    const publicCourse = (id: string, name: string) => ({
      id, name, slug: name.toLowerCase().replace(/\s+/g, "-"), category: "Science",
      subject: null, stream: null, grade: null, academicYear: null, description: null,
      price: 4500, accessDurationDays: 30, enrollmentRule: null, pricingModel: "ONE_TIME",
      resolvedAmount: 4500, currency: "LKR", requiresManualQuote: false,
    });
    await mockStudentSession(page);
    await mockJson(page, "**/api/v1/public/courses*", 200, apiSuccess({
      content: [publicCourse(COURSE_ID, "Biology"), publicCourse(OTHER_ID, "Chemistry")],
      page: 0, size: 20, totalElements: 2, totalPages: 1,
    }));
    await mockJson(page, "**/api/v1/enrollments/my", 200, apiSuccess([makeEnrollment()]));

    await page.goto("/student/browse");

    const list = page.getByRole("list", { name: "Available courses" });
    await expect(list.getByRole("link", { name: "Open course Biology" })).toHaveAttribute(
      "href", `/student/courses/${COURSE_ID}`);
    await expect(list.getByRole("link", { name: "Enroll in Chemistry" })).toHaveAttribute(
      "href", `/student/checkout/${OTHER_ID}`);
    // Stays in the portal shell — the public header's "Sign in" link never appears.
    await expect(page.getByRole("link", { name: "Sign in" })).toHaveCount(0);
  });
});
