import { test, expect } from "@playwright/test";
import { apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

const COURSE_ID = "course-parity-1";
const MODULE_ID = "module-parity-1";
const LESSON_ID = "lesson-parity-1";

function course() {
  return {
    id: COURSE_ID,
    teacherId: "teacher-other",
    name: "Tenant Algebra",
    slug: "tenant-algebra",
    category: "Math",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: null,
    price: 100,
    accessDurationDays: null,
    enrollmentRule: null,
    status: "DRAFT",
    pricingModel: "ONE_TIME",
    archivedAt: null,
    createdAt: "2026-10-06T08:00:00Z",
    updatedAt: "2026-10-06T08:00:00Z",
    resolvedAmount: 100,
    currency: "USD",
    requiresManualQuote: false,
    registrationEnabled: true,
    enrollmentFee: null,
    coverImageUrl: null,
    whatsappInviteUrl: null,
    telegramInviteUrl: null,
    paymentValidDays: null,
    commissionRate: null,
    teachers: [{ id: "teacher-other", name: "Teacher Other", primary: true }],
    tags: [],
    linkedCourses: [],
    displayOrder: null,
    priceDisplay: { amount: 100, currency: "USD", period: null },
  };
}

async function setupAdminCourse(page: import("@playwright/test").Page) {
  const token = fakeJwt({ role: "TENANT_ADMIN" });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
  await page.route("**/api/v1/courses/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}`) && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(course())) });
      return;
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules`) && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([{ id: MODULE_ID, courseId: COURSE_ID, title: "Module 1", sequence: 1 }])) });
      return;
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons`) && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([{ id: LESSON_ID, moduleId: MODULE_ID, title: "Lesson 1", sequence: 1 }])) });
      return;
    }
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}/modules/${MODULE_ID}/lessons/${LESSON_ID}/materials`) && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
  });
}

test.describe("Tenant Admin course edit parity", () => {
  test("Tenant Admin can open Modules & sessions for another teacher's course", async ({ page }) => {
    await setupAdminCourse(page);
    await page.goto(`/tenant-admin/courses/${COURSE_ID}`);
    await page.getByRole("link", { name: "Modules & sessions" }).click();
    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${COURSE_ID}/content$`));
    await expect(page.getByRole("heading", { name: "Modules & sessions" })).toBeVisible();
    // The editor renders titles as editable fields: the admin can edit another teacher's course.
    await expect(page.getByRole("textbox", { name: "Module title" }).first()).toHaveValue("Module 1");
    await expect(page.getByRole("textbox", { name: "Session title" }).first()).toHaveValue("Lesson 1");
    await expect(page.getByRole("link", { name: "Back to modules" })).toHaveAttribute(
      "href",
      `/tenant-admin/courses/${COURSE_ID}/modules`
    );
    await expect(page.getByRole("button", { name: "Choose from Media Library" })).toBeVisible();

    await page.goto(`/tenant-admin/courses/${COURSE_ID}/content#session-${LESSON_ID}`);
    await expect(page.locator(`#session-${LESSON_ID}`)).toBeFocused();
  });

  test("Tenant Admin course workspace exposes roster and course live-class scheduling", async ({ page }) => {
    await setupAdminCourse(page);
    await page.route("**/api/v1/courses/*/roster", async (route) => {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([{ studentId: "student-1", userId: "user-1", name: "Student One", email: "student@example.test" }])) });
    });
    await page.route("**/api/v1/class-sessions**", async (route) => {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
    });

    await page.goto(`/tenant-admin/courses/${COURSE_ID}`);
    await page.getByRole("link", { name: "Roster" }).click();
    await expect(page.getByRole("heading", { name: "Roster" })).toBeVisible();
    // The roster renders a table plus a (small-screen) card list; assert the table cell.
    await expect(page.getByRole("cell", { name: "Student One" })).toBeVisible();

    // The roster is a standalone page (no workspace tab nav): return to the course first.
    await page.getByRole("link", { name: "Back to course" }).click();
    await page.getByRole("navigation", { name: "Course sections" }).getByRole("link", { name: "Live classes", exact: true }).click();
    // Rendered as a link styled as a button (Button render={<Link/>}).
    await expect(page.getByRole("link", { name: "Schedule live class" })).toBeVisible();
    await page.getByRole("link", { name: "Schedule live class" }).click();
    await expect(page).toHaveURL(new RegExp(`/tenant-admin/courses/${COURSE_ID}/live-classes/new$`));
    await expect(page.getByRole("heading", { name: "Schedule a live class" })).toBeVisible();
  });
});
