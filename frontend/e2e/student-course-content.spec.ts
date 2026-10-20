import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";
import {
  contentWith,
  launchAllowed,
  lessonNode,
  mockKlassStudentCourse,
  MODULE_ID,
} from "./fixtures/klass/student-course-mocks";

/**
 * Student "Course Content" (`app/(student)/student/courses/[courseId]/page.tsx`)
 * and the "Open course" entry point on "My courses".
 *
 * KLASS update (ADR-028, plan §4.2/§4.3): "My courses" is now the Enrolled /
 * Available catalog (`GET /v1/enrollments/my/catalog`), enrollment is free
 * participation, and the course page renders the server-evaluated content
 * tree (`GET /courses/{id}/content`). Opening a lesson always asks the server
 * (`POST .../lessons/{id}/launch`) before routing to the existing lesson
 * materials page. The old client-side "EXPIRED enrollment" gate is replaced by
 * the server's per-lesson access status: a lesson whose access is not ALLOWED
 * (expired, unpaid) has no open control and never triggers a launch. A
 * backend 403/404 on the course read still renders fixed local copy - never
 * the backend's message text.
 *
 * Mock-only (no real backend): the server-side checks themselves are proven
 * by `ParticipationAndLessonAccessIntegrationTest` and
 * `KlassLessonAccessAcceptanceIntegrationTest` (incl. cross-tenant denials).
 */

const COURSE_ID = "c0000000-0000-0000-0000-00000000c0de";
const LESSON_ID = "lesson-1";

async function mockStudentSession(page: Page): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
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
    coverImageUrl: null,
    priceDisplay: { amount: "4500.00", currency: "LKR", period: "MONTH" },
    teachers: [{ id: "teacher-1", name: "Teacher One", primary: true }],
    whatsappInviteUrl: null,
    telegramInviteUrl: null,
    timezone: "Asia/Colombo",
    registrationEnabled: true,
  };
}

function catalogCourse(courseId: string, title: string, enrolled: boolean) {
  return {
    courseId,
    title,
    teacherName: "Teacher One",
    coverImageUrl: null,
    priceDisplay: { amount: "4500.00", currency: "LKR", period: "MONTH" },
    pricingModel: "MONTHLY",
    tags: [],
    enrolled,
    registrationEnabled: true,
  };
}

/** `GET /v1/enrollments/my/catalog?view=…` answered per view. */
async function mockCatalog(page: Page, enrolled: ReturnType<typeof catalogCourse>[], available: ReturnType<typeof catalogCourse>[]) {
  await mockJson(page, "**/api/v1/course-tags", 200, apiSuccess([]));
  await page.route("**/api/v1/enrollments/my/catalog*", async (route: Route) => {
    const view = new URL(route.request().url()).searchParams.get("view");
    const rows = view === "AVAILABLE" ? available : enrolled;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(apiSuccess({ content: rows, page: 0, size: 20, totalElements: rows.length, totalPages: 1 })),
    });
  });
}

test.describe("student — My courses entry point", () => {
  test("an enrolled course's card links to the course content page", async ({ page }) => {
    await mockStudentSession(page);
    await mockCatalog(page, [catalogCourse(COURSE_ID, "2028 Biology Theory Class", true)], []);

    await page.goto("/student/courses");

    const open = page.getByRole("link", { name: "Open course 2028 Biology Theory Class" });
    await expect(open).toHaveAttribute("href", `/student/courses/${COURSE_ID}`);
  });

  // Was "an EXPIRED enrollment's card offers no Open course link". Under
  // ADR-028 participation never expires (access is per lesson); the kept
  // intent is that a course the student does not participate in offers no
  // "Open course" link - only View / free Enroll.
  test("a course the student is not enrolled in offers View and Enroll, never an Open course link", async ({ page }) => {
    await mockStudentSession(page);
    await mockCatalog(page, [], [catalogCourse(COURSE_ID, "2028 Biology Theory Class", false)]);

    await page.goto("/student/courses?view=available");

    await expect(page.getByRole("button", { name: "Enroll in 2028 Biology Theory Class" })).toBeVisible();
    await expect(page.getByRole("link", { name: "View course 2028 Biology Theory Class" })).toBeVisible();
    await expect(page.getByRole("link", { name: /^Open course/ })).toHaveCount(0);
  });
});

test.describe("student — course content page", () => {
  test("an enrolled student sees the course, its modules and lessons, and opening a lesson asks the server before showing materials", async ({
    page,
  }) => {
    await mockStudentSession(page);
    const lesson = lessonNode({ lessonId: LESSON_ID, title: "Lesson 01", courseId: COURSE_ID });
    const content = contentWith([lesson], COURSE_ID);
    content.periods[0].modules[0].title = "Online Live class";
    const mocks = await mockKlassStudentCourse(page, {
      courseId: COURSE_ID,
      course: makeCourse(),
      content,
      launches: {
        [LESSON_ID]: {
          status: 200,
          body: { success: true, data: launchAllowed([{ kind: "MATERIAL", id: "material-1", title: "Notes", type: "PDF" }]) },
        },
      },
    });

    await page.goto(`/student/courses/${COURSE_ID}`);

    await expect(page.getByRole("heading", { name: "2028 Biology Theory Class" })).toBeVisible();
    await expect(page.getByTestId("course-content-progress")).toHaveText("0 of 1 viewed");
    await page.getByTestId("content-period-toggle").click();
    await page.getByRole("button", { name: "Online Live class" }).click();
    await page.getByTestId("content-lesson").filter({ hasText: "Lesson 01" }).getByTestId("content-lesson-open").click();

    await expect(page).toHaveURL(
      new RegExp(`/student/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials$`)
    );
    expect(mocks.launchedLessonIds).toEqual([LESSON_ID]);
  });

  test("a lesson the server does not allow (expired or unpaid) has no open control and never launches", async ({ page }) => {
    await mockStudentSession(page);
    const expired = lessonNode({
      lessonId: "lesson-expired",
      title: "Ended lesson",
      courseId: COURSE_ID,
      access: { status: "EXPIRED", availability: "ENDED", payment: "COVERED", requiredPurchase: null },
    });
    const unpaid = lessonNode({
      lessonId: "lesson-unpaid",
      title: "Unpaid lesson",
      courseId: COURSE_ID,
      access: {
        status: "PAYMENT_REQUIRED",
        availability: "ALWAYS",
        payment: "REQUIRED",
        requiredPurchase: { scope: "MONTHLY", courseId: COURSE_ID, moduleId: null, lessonId: null, year: 2026, month: 10 },
      },
    });
    const mocks = await mockKlassStudentCourse(page, {
      courseId: COURSE_ID,
      course: makeCourse(),
      content: contentWith([expired, unpaid], COURSE_ID),
    });

    await page.goto(`/student/courses/${COURSE_ID}`);
    await page.getByTestId("content-period-toggle").click();
    await page.getByTestId("content-module-toggle").click();

    const expiredRow = page.getByTestId("content-lesson").filter({ hasText: "Ended lesson" });
    await expect(expiredRow.getByTestId("lesson-status")).toHaveText("Expired");
    await expect(expiredRow.getByTestId("content-lesson-open")).toHaveCount(0);
    const unpaidRow = page.getByTestId("content-lesson").filter({ hasText: "Unpaid lesson" });
    await expect(unpaidRow.getByTestId("lesson-pay-now")).toBeVisible();
    await expect(unpaidRow.getByTestId("content-lesson-open")).toHaveCount(0);
    await expect(page.getByRole("link", { name: /Lesson|materials/i })).toHaveCount(0);
    expect(mocks.launchedLessonIds).toEqual([]);
  });

  test("a course the Student is not enrolled in shows the not-enrolled state with free Enroll, and no content", async ({
    page,
  }) => {
    await mockStudentSession(page);
    const mocks = await mockKlassStudentCourse(page, {
      courseId: COURSE_ID,
      course: { ...makeCourse(), status: "PUBLIC" },
      contentError: { status: 403, code: "NOT_ENROLLED", message: "Not enrolled" },
    });

    await page.goto(`/student/courses/${COURSE_ID}`);

    await expect(page.getByTestId("course-not-enrolled")).toContainText("You're not enrolled in this course");
    await expect(page.getByTestId("course-detail-enroll")).toBeVisible();
    await expect(page.getByTestId("content-lesson")).toHaveCount(0);
    expect(mocks.launchedLessonIds).toEqual([]);
  });

  test("a backend 403 on the course read renders fixed copy, never the backend's message", async ({ page }) => {
    const backendMessage = "xyz-should-never-render-in-ui";
    await mockStudentSession(page);
    await mockJson(page, `**/api/v1/courses/${COURSE_ID}`, 403, apiError("FORBIDDEN", backendMessage));
    await mockJson(page, `**/api/v1/courses/${COURSE_ID}/content`, 403, apiError("FORBIDDEN", backendMessage));

    await page.goto(`/student/courses/${COURSE_ID}`);

    await expect(page.getByText("This course isn't available")).toBeVisible();
    await expect(page.getByText(backendMessage)).toHaveCount(0);
  });
});

test.describe("student — Browse courses (in-portal catalog)", () => {
  // KLASS (D19): the old Browse page's Enroll went to checkout; enrollment is
  // now free. `/student/browse` redirects to the Available view, whose Enroll
  // never links to checkout, and an enrolled course offers Open course.
  test("old /student/browse lands on Available Courses inside the portal: Enroll is never a checkout link, an enrolled course offers Open course", async ({
    page,
  }) => {
    const OTHER_ID = "c0000000-0000-0000-0000-0000000000aa";
    await mockStudentSession(page);
    await mockCatalog(
      page,
      [catalogCourse(COURSE_ID, "Biology", true)],
      [catalogCourse(OTHER_ID, "Chemistry", false)]
    );

    await page.goto("/student/browse");

    await expect(page).toHaveURL(/\/student\/courses\?view=available$/);
    await expect(page.getByRole("button", { name: "Enroll in Chemistry" })).toBeVisible();
    await expect(page.locator(`a[href^="/student/checkout/"]`)).toHaveCount(0);
    // Stays in the portal shell — the public header's "Sign in" link never appears.
    await expect(page.getByRole("link", { name: "Sign in" })).toHaveCount(0);

    await page.getByTestId("catalog-view-enrolled").click();
    await expect(page.getByRole("link", { name: "Open course Biology" })).toHaveAttribute(
      "href",
      `/student/courses/${COURSE_ID}`
    );
  });
});
