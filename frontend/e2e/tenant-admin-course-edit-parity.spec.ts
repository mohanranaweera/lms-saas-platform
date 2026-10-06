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
  test("Tenant Admin can open Modules & lessons for another teacher's course", async ({ page }) => {
    await setupAdminCourse(page);
    await page.goto(`/tenant-admin/courses/${COURSE_ID}`);
    await page.getByRole("link", { name: "Modules & lessons" }).click();
    await expect(page.getByRole("heading", { name: "Modules & lessons" })).toBeVisible();
    await expect(page.getByText("Module 1")).toBeVisible();
    await expect(page.getByText("Lesson 1")).toBeVisible();
    await expect(page.getByRole("button", { name: "Choose from Media Library" })).toBeVisible();
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
    await expect(page.getByText("Student One")).toBeVisible();

    await page.getByRole("link", { name: "Sessions" }).click();
    await expect(page.getByRole("button", { name: "Schedule live class" })).toBeVisible();
    await page.getByRole("button", { name: "Schedule live class" }).click();
    await expect(page.getByRole("heading", { name: "Schedule a live class" })).toBeVisible();
  });
});
