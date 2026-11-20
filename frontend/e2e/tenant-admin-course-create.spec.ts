import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  loginResponseBody,
  mockJson,
  refreshResponseBody,
  TEACHER_LIST_URL,
} from "./fixtures/auth-mocks";

/**
 * Staff-facing course creation. KLASS (EP-C01) replaced the multi-step
 * `tenant-admin/courses/new` form with the course list's "Add course" dialog
 * (`components/courses/admin/course-create-dialog.tsx`); `/new` now redirects
 * to the list. No real backend runs in this environment (see
 * `fixtures/auth-mocks.ts`).
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
  await mockJson(page, TEACHER_LIST_URL, 200, apiSuccess([]));
  await mockJson(page, "**/v1/course-tags", 200, apiSuccess([]));
  await page.goto("/login");
  await page.getByLabel("Email").fill("admin@example.com");
  await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/tenant-admin\/dashboard$/);
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

/** R2: the Overview's Klass edit form reads these `CourseResponse` fields. */
function createdKlassCourseFixture(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    ...createdCourseFixture(overrides),
    teachers: [{ id: TEACHER_ID, name: "Teacher Nine", primary: true }],
    tags: [],
    linkedCourses: [],
    priceDisplay: null,
    enrollmentFee: "0.00",
    paymentValidDays: null,
    commissionRate: null,
    whatsappInviteUrl: null,
    telegramInviteUrl: null,
    coverImageUrl: null,
    registrationEnabled: true,
    displayOrder: null,
    timezone: "Asia/Colombo",
    ...overrides,
  };
}

const APPROVED_TEACHER = {
  id: "teacher-profile-9",
  userId: TEACHER_ID,
  name: "Teacher Nine",
  email: "teacher.nine@example.test",
  approvalStatus: "APPROVED",
  accountStatus: "ACTIVE",
  approvedBy: null,
  approvedAt: null,
};

async function openAddCourseDialog(page: Page) {
  await page.getByRole("link", { name: "Courses" }).click();
  await page.getByTestId("courses-add-course").click();
  const dialog = page.getByTestId("add-course-dialog");
  await expect(dialog.getByRole("heading", { name: "Add New Course" })).toBeVisible();
  return dialog;
}

async function fillValidCourse(page: Page) {
  const dialog = page.getByTestId("add-course-dialog");
  await dialog.getByTestId("add-course-name").fill("Intro to Chemistry");
  await dialog.getByTestId("add-course-teachers").getByRole("button").first().click();
  await dialog.getByTestId("add-course-teachers").getByRole("checkbox", { name: "Teacher Nine" }).check();
  await dialog.getByTestId("add-course-payment-type").selectOption("MONTHLY");
  await dialog.getByTestId("add-course-price").fill("3500");
}

test.describe("tenant admin course create — Add course dialog", () => {
  test("the legacy /new route redirects to the course list, where Add course opens the dialog", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await page.goto("/tenant-admin/courses/new");
    await expect(page).toHaveURL(/\/tenant-admin\/courses$/);
    await page.getByTestId("courses-add-course").click();
    await expect(page.getByTestId("add-course-dialog").getByRole("heading", { name: "Add New Course" })).toBeVisible();
  });

  test("submits teacherIds, pricing model and price, then confirms the draft course", async ({ page }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, TEACHER_LIST_URL, 200, apiSuccess([APPROVED_TEACHER]));

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
        body: JSON.stringify(apiSuccess(createdKlassCourseFixture({ pricingModel: "MONTHLY", price: 3500 }))),
      });
    });

    const dialog = await openAddCourseDialog(page);
    await fillValidCourse(page);
    await dialog.getByTestId("add-course-submit").click();

    await expect(page.getByText("Course created as a draft. Publish it to show it to students.")).toBeVisible();
    expect(postBody).toMatchObject({
      name: "Intro to Chemistry",
      teacherIds: [TEACHER_ID],
      pricingModel: "MONTHLY",
      price: "3500.00",
    });
  });

  test("a 403 on submit surfaces inline in the dialog, without a full-page permission-denied takeover", async ({
    page,
  }) => {
    await loginAsTenantAdmin(page);
    await mockJson(page, TEACHER_LIST_URL, 200, apiSuccess([APPROVED_TEACHER]));
    await page.route("**/api/v1/courses", async (route) => {
      if (route.request().method() !== "POST") {
        await route.fallback();
        return;
      }
      await route.fulfill({
        status: 403,
        contentType: "application/json",
        body: JSON.stringify(apiError("FORBIDDEN", "You do not have permission to create a course.")),
      });
    });

    const dialog = await openAddCourseDialog(page);
    await fillValidCourse(page);
    await dialog.getByTestId("add-course-submit").click();

    await expect(dialog.getByTestId("add-course-form-error")).toContainText("You don't have permission to create courses.");
    await expect(dialog).toBeVisible();
    await expect(page).toHaveURL(/\/tenant-admin\/courses$/);
  });
});
