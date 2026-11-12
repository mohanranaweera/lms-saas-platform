import { test, expect, type Page, type Route } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, fulfillJson, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";
import { contentWith, lessonNode, mockKlassStudentCourse } from "./fixtures/klass/student-course-mocks";

/**
 * Teacher Portal (TP-02/TP-03): "Open course" renders the student course
 * layout fed by the teacher preview (no progress, no payment prompts, no
 * launch/progress write), management actions sit behind "Manage course",
 * and My Profile edits only the server's allowlist.
 *
 * Mock-only: assignment scoping, co-teacher view-not-manage, removal and
 * cross-tenant denials are proven server-side by
 * `TeacherPortalAccessIntegrationTest`.
 */

const COURSE_ID = "c0000000-0000-0000-0000-00000000c0de";
const SECOND_MODULE_ID = "bbbbbbbb-0000-0000-0000-000000000002";

async function mockTeacherSession(page: Page): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TEACHER" }))));
}

function course() {
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
    coverImageUrl: null,
    priceDisplay: { amount: "4500.00", currency: "LKR", period: "MONTH" },
    teachers: [
      { id: "teacher-1", name: "Teacher One", primary: true },
      { id: "teacher-2", name: "Teacher Two", primary: false },
    ],
    whatsappInviteUrl: null,
    telegramInviteUrl: null,
    timezone: "Asia/Colombo",
    registrationEnabled: true,
  };
}

/** Two ordered modules, as the server returns them (sequence order). */
function twoModuleContent() {
  const base = contentWith(
    [
      lessonNode({ lessonId: "lesson-1", title: "Lesson 01 - Cells", courseId: COURSE_ID, paymentType: "PAID" }),
      lessonNode({ lessonId: "lesson-2", title: "Lesson 02 - Tissues", courseId: COURSE_ID }),
    ],
    COURSE_ID
  );
  const period = base.periods[0];
  return {
    ...base,
    totalCount: 3,
    periods: [
      {
        ...period,
        modules: [
          { ...period.modules[0], title: "Week 1", sequence: 1 },
          {
            moduleId: SECOND_MODULE_ID,
            title: "Week 2",
            sequence: 2,
            sourceCourseId: null,
            lessons: [
              lessonNode({
                lessonId: "lesson-3",
                title: "Lesson 03 - Organs",
                courseId: COURSE_ID,
                moduleId: SECOND_MODULE_ID,
              }),
            ],
          },
        ],
      },
    ],
  };
}

async function expandAll(page: Page) {
  await page.getByTestId("content-period-toggle").click();
  const toggles = page.getByTestId("content-module-toggle");
  for (let i = 0; i < (await toggles.count()); i += 1) await toggles.nth(i).click();
}

async function openCourseAndAssertSharedLayout(page: Page) {
  const handle = await mockKlassStudentCourse(page, { courseId: COURSE_ID, course: course(), content: twoModuleContent() });
  const forbidden: string[] = [];
  page.on("request", (request) => {
    const url = request.url();
    if (/\/enrollments\/my|\/checkout\/|\/payments\/|\/attendance\//.test(url) && request.method() !== "GET") {
      forbidden.push(`${request.method()} ${url}`);
    }
  });

  await page.goto(`/teacher/courses/${COURSE_ID}`);

  await expect(page.getByTestId("teacher-course-view")).toBeVisible();
  await expect(page.getByTestId("course-header-card")).toBeVisible();
  await expect(page.getByTestId("course-detail-title")).toHaveText("2028 Biology Theory Class");
  await expect(page.getByTestId("course-header-card")).toContainText("Teacher One, Teacher Two");
  await expect(page.getByRole("tab", { name: "Course content" })).toBeVisible();
  // Student-only surfaces are absent: progress, enroll, pay.
  await expect(page.getByTestId("course-content-progress")).toHaveCount(0);
  await expect(page.getByTestId("course-not-enrolled")).toHaveCount(0);

  await expandAll(page);
  await expect(page.getByTestId("content-module")).toHaveCount(2);
  await expect(page.getByTestId("content-module").nth(0)).toContainText("Week 1");
  await expect(page.getByTestId("content-module").nth(1)).toContainText("Week 2");
  await expect(page.getByTestId("content-lesson").nth(0)).toContainText("Lesson 01 - Cells");
  await expect(page.getByTestId("content-lesson").nth(2)).toContainText("Lesson 03 - Organs");
  await expect(page.getByRole("button", { name: /Pay now/ })).toHaveCount(0);

  // Opening a session goes to its materials - never the student launch (progress) call.
  await page.route(`**/v1/courses/${COURSE_ID}/modules/**`, (route: Route) => fulfillJson(route, 200, apiSuccess([])));
  await page.getByTestId("content-lesson").filter({ hasText: "Lesson 03" }).getByTestId("content-lesson-open").click();
  await expect(page).toHaveURL(
    new RegExp(`/teacher/courses/${COURSE_ID}/modules/${SECOND_MODULE_ID}/lessons/lesson-3/materials$`),
    // First visit compiles the route in `next dev` (see playwright.config.ts cold-compile note).
    { timeout: 30_000 }
  );
  await expect(page.getByRole("heading", { name: "Lesson materials" })).toBeVisible();
  expect(handle.launchedLessonIds).toEqual([]);
  expect(forbidden).toEqual([]);
}

test.describe("teacher portal — course view", () => {
  test("desktop: Open course renders the student layout with ordered modules and no payment or progress", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 });
    await mockTeacherSession(page);
    await openCourseAndAssertSharedLayout(page);
  });

  test("narrow screen: same layout, reachable without horizontal scroll", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 800 });
    await mockTeacherSession(page);
    await mockKlassStudentCourse(page, { courseId: COURSE_ID, course: course(), content: twoModuleContent() });

    await page.goto(`/teacher/courses/${COURSE_ID}`);
    await expect(page.getByTestId("course-detail-title")).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow).toBeLessThanOrEqual(1);
    await expandAll(page);
    await expect(page.getByTestId("content-lesson")).toHaveCount(3);
  });

  test("Manage course keeps the existing management deep links", async ({ page }) => {
    await mockTeacherSession(page);
    await mockKlassStudentCourse(page, { courseId: COURSE_ID, course: course(), content: twoModuleContent() });

    await page.goto(`/teacher/courses/${COURSE_ID}`);
    await page.getByRole("button", { name: "Manage course 2028 Biology Theory Class" }).click();
    const menu = page.getByRole("menu");
    await expect(menu.getByRole("menuitem", { name: "Edit details" })).toHaveAttribute(
      "href",
      `/teacher/courses/${COURSE_ID}/edit`
    );
    await expect(menu.getByRole("menuitem", { name: "Modules & sessions" })).toHaveAttribute(
      "href",
      `/teacher/courses/${COURSE_ID}/modules`
    );
  });

  test("an empty course shows the teacher empty state", async ({ page }) => {
    await mockTeacherSession(page);
    await mockKlassStudentCourse(page, { courseId: COURSE_ID, course: course(), content: contentWith([], COURSE_ID) });

    await page.goto(`/teacher/courses/${COURSE_ID}`);
    await expect(page.getByTestId("course-content-empty")).toContainText("no modules or sessions yet");
  });

  test("an unassigned course link (403) shows the unavailable state, not the course", async ({ page }) => {
    await mockTeacherSession(page);
    await page.route(`**/v1/courses/${COURSE_ID}**`, (route: Route) =>
      fulfillJson(route, 403, apiError("FORBIDDEN", "You do not have permission to perform this action"))
    );

    await page.goto(`/teacher/courses/${COURSE_ID}`);
    await expect(page.getByTestId("teacher-course-unavailable")).toBeVisible();
    await expect(page.getByTestId("course-detail-title")).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Back to My Courses" })).toHaveAttribute("href", "/teacher/courses");
  });
});

// ---------------------------------------------------------------------------
// My Profile
// ---------------------------------------------------------------------------

function profile(overrides: Record<string, unknown> = {}) {
  return {
    id: "profile-1",
    userId: "teacher-1",
    name: "Teacher One",
    firstName: "Teacher",
    lastName: "One",
    email: "teacher@example.com",
    contactNumber: "0771234567",
    qualifications: null,
    description: null,
    profileImageUrl: null,
    approvalStatus: "APPROVED",
    editableFields: ["qualifications", "description"],
    ...overrides,
  };
}

test.describe("teacher portal — My Profile", () => {
  test("is reachable from the nav, shows the profile, and saves only allowlisted fields", async ({ page }) => {
    await mockTeacherSession(page);
    let stored = profile();
    const patches: Record<string, unknown>[] = [];
    await page.route("**/v1/teachers/me", async (route: Route) => {
      if (route.request().method() === "PATCH") {
        const body = route.request().postDataJSON() as Record<string, unknown>;
        patches.push(body);
        stored = profile({ qualifications: body.qualifications, description: body.description });
      }
      await fulfillJson(route, 200, apiSuccess(stored));
    });

    await page.goto("/teacher/profile");
    await expect(page.getByRole("link", { name: "My Profile" })).toHaveAttribute("href", "/teacher/profile");
    await expect(page.getByTestId("teacher-profile-name")).toHaveText("Teacher One");
    await expect(page.getByText("0771234567")).toBeVisible();
    // Names/contact are display-only - no inputs for them.
    await expect(page.getByLabel("First name")).toHaveCount(0);

    await page.getByLabel("Qualifications").fill("BSc (Hons) Biology");
    await page.getByLabel("Biography").fill("Ten years of A/L biology.");
    await page.getByTestId("teacher-profile-save").click();
    await expect(page.getByTestId("teacher-profile-saved")).toBeVisible();
    expect(patches).toEqual([{ qualifications: "BSc (Hons) Biology", description: "Ten years of A/L biology." }]);

    await page.reload();
    await expect(page.getByLabel("Qualifications")).toHaveValue("BSc (Hons) Biology");
  });

  test("validates length client-side and surfaces a server error", async ({ page }) => {
    await mockTeacherSession(page);
    await page.route("**/v1/teachers/me", async (route: Route) => {
      if (route.request().method() === "PATCH") {
        return fulfillJson(route, 500, apiError("INTERNAL_ERROR", "boom"));
      }
      return fulfillJson(route, 200, apiSuccess(profile()));
    });

    await page.goto("/teacher/profile");
    await page.getByLabel("Qualifications").fill("x".repeat(2001));
    await page.getByTestId("teacher-profile-save").click();
    await expect(page.getByText("Qualifications must be 2000 characters or fewer.")).toBeVisible();

    await page.getByLabel("Qualifications").fill("MSc");
    await page.getByTestId("teacher-profile-save").click();
    await expect(page.getByTestId("teacher-profile-error")).toHaveText("We couldn't save your profile. Try again.");
  });

  test("a missing teacher record shows the unavailable state", async ({ page }) => {
    await mockTeacherSession(page);
    await page.route("**/v1/teachers/me", (route: Route) =>
      fulfillJson(route, 404, apiError("NOT_FOUND", "Teacher profile not found"))
    );

    await page.goto("/teacher/profile");
    await expect(page.getByTestId("teacher-profile-unavailable")).toBeVisible();
  });
});
