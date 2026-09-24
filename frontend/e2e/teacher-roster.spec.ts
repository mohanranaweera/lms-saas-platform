import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Teacher Roster (Wave 3, PAR-03-06) —
 * `(teacher)/teacher/courses/[courseId]/roster`. No real backend runs in
 * this environment — `GET /v1/courses/{courseId}/roster` is backend-filtered
 * to the caller's own course ownership for a Teacher caller; this spec
 * exercises the client-side handling of both the success and the
 * cross-teacher-course rejection responses, not the backend enforcement
 * itself (which is covered by the backend's own integration tests).
 */

const COURSE = {
  id: "course-owned-1",
  teacherId: "teacher-1",
  name: "Intro to Biology",
  slug: "intro-bio",
  category: "Science",
  subject: null,
  stream: null,
  grade: null,
  academicYear: null,
  description: null,
  price: 49.99,
  accessDurationDays: null,
  enrollmentRule: null,
  status: "PUBLIC",
  pricingModel: "ONE_TIME",
  archivedAt: null,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  resolvedAmount: 49.99,
  currency: "USD",
  requiresManualQuote: false,
};

async function mockTeacherSession(page: Page): Promise<void> {
  const token = fakeJwt({ role: "TEACHER" });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

test.describe("teacher roster — own course", () => {
  test("renders the backend-filtered roster for the teacher's own course", async ({ page }) => {
    await mockTeacherSession(page);
    await mockJson(page, `**/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
    await mockJson(
      page,
      `**/v1/courses/${COURSE.id}/roster`,
      200,
      apiSuccess([
        { studentId: "s1", userId: "u1", name: "Ada Lovelace", email: "ada@example.test" },
        { studentId: "s2", userId: "u2", name: "Grace Hopper", email: "grace@example.test" },
      ])
    );

    await page.goto(`/teacher/courses/${COURSE.id}/roster`);

    await expect(page.getByText(`Roster for ${COURSE.name}.`)).toBeVisible();
    const table = page.getByRole("table");
    await expect(table.getByText("Ada Lovelace")).toBeVisible();
    await expect(table.getByText("Grace Hopper")).toBeVisible();
  });

  test("an empty roster shows a dedicated empty state", async ({ page }) => {
    await mockTeacherSession(page);
    await mockJson(page, `**/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
    await mockJson(page, `**/v1/courses/${COURSE.id}/roster`, 200, apiSuccess([]));

    await page.goto(`/teacher/courses/${COURSE.id}/roster`);
    await expect(page.getByText("No enrolled students")).toBeVisible();
  });

  test("a loading state renders before the roster resolves", async ({ page }) => {
    await mockTeacherSession(page);
    await mockJson(page, `**/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
    await page.route(`**/v1/courses/${COURSE.id}/roster`, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 400));
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
    });

    await page.goto(`/teacher/courses/${COURSE.id}/roster`);
    await expect(page.getByText("Loading roster…")).toBeVisible();
  });
});

test.describe("teacher roster — another teacher's course is rejected, never a client-filtered subset", () => {
  test("a 403/404 for a non-owned course renders the shared error/permission-denied handling, not a roster table", async ({
    page,
  }) => {
    await mockTeacherSession(page);
    const otherCourseId = "course-not-owned-1";
    await mockJson(
      page,
      `**/v1/courses/${otherCourseId}`,
      404,
      apiError("NOT_FOUND", "Course not found")
    );

    await page.goto(`/teacher/courses/${otherCourseId}/roster`);

    await expect(page.getByRole("alert").filter({ hasText: "Course not found" })).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
  });

  test("a course the teacher can see but does not own is rejected by the roster endpoint itself (403)", async ({
    page,
  }) => {
    await mockTeacherSession(page);
    const otherCourseId = "course-not-owned-2";
    await mockJson(
      page,
      `**/v1/courses/${otherCourseId}`,
      200,
      apiSuccess({ ...COURSE, id: otherCourseId, teacherId: "some-other-teacher" })
    );
    await mockJson(
      page,
      `**/v1/courses/${otherCourseId}/roster`,
      403,
      apiError("FORBIDDEN", "You do not have permission to view this course's roster.")
    );

    await page.goto(`/teacher/courses/${otherCourseId}/roster`);

    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
  });
});
