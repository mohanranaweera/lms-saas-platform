import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Tenant Admin course Overview: the amber "Students can't pay for this course
 * yet" panel driven by `GET /api/v1/courses/{id}/payment-readiness`. Fully
 * mocked - shown only for `payable: false`, hidden for a payable course and
 * when the readiness request fails (it never blocks the page).
 */
const COURSE_ID = "course-readiness-1";
const READINESS_URL = `**/api/v1/courses/${COURSE_ID}/payment-readiness`;

function course() {
  return {
    id: COURSE_ID,
    teacherId: "teacher-1",
    name: "Monthly Physics",
    slug: "monthly-physics",
    category: "Science",
    subject: null,
    stream: null,
    grade: null,
    academicYear: null,
    description: null,
    price: 4000,
    accessDurationDays: null,
    enrollmentRule: null,
    status: "DRAFT",
    pricingModel: "MONTHLY",
    archivedAt: null,
    createdAt: "2026-10-06T08:00:00Z",
    updatedAt: "2026-10-06T08:00:00Z",
    resolvedAmount: 4000,
    currency: "LKR",
    requiresManualQuote: false,
    registrationEnabled: true,
    enrollmentFee: null,
    coverImageUrl: null,
    whatsappInviteUrl: null,
    telegramInviteUrl: null,
    paymentValidDays: null,
    commissionRate: null,
    teachers: [{ id: "teacher-1", name: "Teacher One", primary: true }],
    tags: [],
    linkedCourses: [],
    displayOrder: null,
    priceDisplay: { amount: 4000, currency: "LKR", period: "MONTH" },
  };
}

async function setupAdminCourse(page: Page) {
  const token = fakeJwt({ role: "TENANT_ADMIN" });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
  await page.route("**/api/v1/courses/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith(`/api/v1/courses/${COURSE_ID}`) && request.method() === "GET") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(course())) });
      return;
    }
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
  });
}

async function openOverview(page: Page) {
  const readiness = page.waitForResponse((response) => response.url().endsWith("/payment-readiness"));
  await page.goto(`/tenant-admin/courses/${COURSE_ID}`);
  await expect(page.getByRole("heading", { name: "Monthly Physics", level: 1 })).toBeVisible();
  await readiness;
}

test.describe("Course payment readiness panel", () => {
  test("lists the issues when students can't pay for the course", async ({ page }) => {
    await setupAdminCourse(page);
    await mockJson(page, READINESS_URL, 200, apiSuccess({
      pricingModel: "MONTHLY",
      payable: false,
      issues: [
        {
          code: "MONTHLY_SESSIONS_WITHOUT_MONTH",
          message: "2 sessions covered by class fees have a start date but no billing month.",
          count: 2,
        },
      ],
    }));

    await openOverview(page);

    const panel = page.getByRole("status").filter({ hasText: "Students can't pay for this course yet" });
    await expect(panel).toBeVisible();
    await expect(panel.getByRole("heading", { name: "Students can't pay for this course yet" })).toBeVisible();
    await expect(panel.getByRole("listitem")).toHaveText([
      "2 sessions covered by class fees have a start date but no billing month.",
    ]);
  });

  test("stays hidden for a payable course", async ({ page }) => {
    await setupAdminCourse(page);
    await mockJson(page, READINESS_URL, 200, apiSuccess({ pricingModel: "MONTHLY", payable: true, issues: [] }));

    await openOverview(page);

    await expect(page.getByTestId("course-payment-readiness")).toHaveCount(0);
    await expect(page.getByText("Students can't pay for this course yet")).toHaveCount(0);
  });

  test("a failed readiness request hides the panel and never blocks the page", async ({ page }) => {
    await setupAdminCourse(page);
    await mockJson(page, READINESS_URL, 500, apiError("INTERNAL_ERROR", "An unexpected error occurred"));

    await openOverview(page);

    await expect(page.getByTestId("course-payment-readiness")).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Monthly Physics", level: 1 })).toBeVisible();
  });
});
