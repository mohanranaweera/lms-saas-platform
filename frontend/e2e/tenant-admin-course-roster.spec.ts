import { test, expect, type Page } from "@playwright/test";
import { apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Tenant Admin course roster — `(tenant-admin)/tenant-admin/courses/[courseId]/roster`.
 * With View Course Payment Status (Tenant Admin holds it) the backend lists only
 * enrolled students with access right now (paid for the current month, an active
 * Free Card, or a free course) with a display-only `accessStatus`. No real backend runs here — the filter and status are
 * backend-computed and covered by `CourseRosterParticipationIntegrationTest`.
 */

const COURSE = {
  id: "course-chem-2027",
  teacherId: "teacher-1",
  name: "2027 Chemistry Theory Class",
  slug: "chem-2027",
  category: "Science",
  subject: null,
  stream: null,
  grade: null,
  academicYear: null,
  description: null,
  price: 3500,
  accessDurationDays: 30,
  enrollmentRule: null,
  status: "PUBLIC",
  pricingModel: "MONTHLY",
  archivedAt: null,
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString(),
  resolvedAmount: 3500,
  currency: "LKR",
  requiresManualQuote: false,
};

async function mockTenantAdminSession(page: Page): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "TENANT_ADMIN" }))));
  await mockJson(
    page,
    "**/v1/me/permissions",
    200,
    apiSuccess({ role: "TENANT_ADMIN", capabilities: ["COURSES_VIEW_PAYMENT_STATUS"], permissionVersion: 1 })
  );
}

test("lists the active students with their paid-access status", async ({ page }) => {
  await mockTenantAdminSession(page);
  await mockJson(page, `**/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
  await mockJson(
    page,
    `**/v1/courses/${COURSE.id}/roster`,
    200,
    apiSuccess([
      { studentId: "s1", userId: "u1", name: "Paid Student", email: "paid@example.test", accessStatus: "PAID" },
      { studentId: "s3", userId: "u3", name: "Card Student", email: "card@example.test", accessStatus: "FREE_CARD" },
    ])
  );

  await page.goto(`/tenant-admin/courses/${COURSE.id}/roster`);

  await expect(page.getByText(`Roster for ${COURSE.name}.`)).toBeVisible();
  const table = page.getByRole("table");
  await expect(table.getByRole("columnheader", { name: "Paid access" })).toBeVisible();
  await expect(table.getByRole("row", { name: /Paid Student/ }).getByText("Paid", { exact: true })).toBeVisible();
  await expect(table.getByRole("row", { name: /Card Student/ }).getByText("Free card")).toBeVisible();
  await expect(page.getByText("No active students")).toHaveCount(0);
});

test("a course with no active students shows the dedicated empty state", async ({ page }) => {
  await mockTenantAdminSession(page);
  await mockJson(page, `**/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
  await mockJson(page, `**/v1/courses/${COURSE.id}/roster`, 200, apiSuccess([]));

  await page.goto(`/tenant-admin/courses/${COURSE.id}/roster`);

  await expect(page.getByText("No active students")).toBeVisible();
  await expect(
    page.getByText("No enrolled student has paid for this month or holds an active Free Card.")
  ).toBeVisible();
});

test("below md, each roster card still shows the paid-access status", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await mockTenantAdminSession(page);
  await mockJson(page, `**/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
  await mockJson(
    page,
    `**/v1/courses/${COURSE.id}/roster`,
    200,
    apiSuccess([
      { studentId: "s2", userId: "u2", name: "Card Student", email: "card@example.test", accessStatus: "FREE_CARD" },
    ])
  );

  await page.goto(`/tenant-admin/courses/${COURSE.id}/roster`);

  await expect(page.getByRole("table")).toBeHidden();
  const cards = page.getByRole("list");
  await expect(cards.getByText("Card Student")).toBeVisible();
  await expect(cards.getByText("Free card")).toBeVisible();
});
