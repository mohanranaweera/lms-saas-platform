import { test, expect, type Page } from "@playwright/test";
import { apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Tenant Admin course roster — `(tenant-admin)/tenant-admin/courses/[courseId]/roster`.
 * The backend roster lists every enrolled student (free enrollment, ADR-028)
 * with a display-only `accessStatus`; a student whose payment lapsed or who has
 * not paid this month is still on the roster, shown as "Not paid". No real
 * backend runs here — membership and status are backend-computed and covered by
 * `CourseRosterParticipationIntegrationTest`.
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
}

test("lists every enrolled student with their paid-access status", async ({ page }) => {
  await mockTenantAdminSession(page);
  await mockJson(page, `**/v1/courses/${COURSE.id}`, 200, apiSuccess(COURSE));
  await mockJson(
    page,
    `**/v1/courses/${COURSE.id}/roster`,
    200,
    apiSuccess([
      { studentId: "s1", userId: "u1", name: "Paid Student", email: "paid@example.test", accessStatus: "PAID" },
      { studentId: "s2", userId: "u2", name: "Lapsed Student", email: "lapsed@example.test", accessStatus: "NOT_PAID" },
      { studentId: "s3", userId: "u3", name: "Card Student", email: "card@example.test", accessStatus: "FREE_CARD" },
    ])
  );

  await page.goto(`/tenant-admin/courses/${COURSE.id}/roster`);

  await expect(page.getByText(`Roster for ${COURSE.name}.`)).toBeVisible();
  const table = page.getByRole("table");
  await expect(table.getByRole("columnheader", { name: "Paid access" })).toBeVisible();
  await expect(table.getByRole("row", { name: /Paid Student/ }).getByText("Paid", { exact: true })).toBeVisible();
  await expect(table.getByRole("row", { name: /Lapsed Student/ }).getByText("Not paid")).toBeVisible();
  await expect(table.getByRole("row", { name: /Card Student/ }).getByText("Free card")).toBeVisible();
  await expect(page.getByText("No enrolled students")).toHaveCount(0);
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
      { studentId: "s2", userId: "u2", name: "Lapsed Student", email: "lapsed@example.test", accessStatus: "NOT_PAID" },
    ])
  );

  await page.goto(`/tenant-admin/courses/${COURSE.id}/roster`);

  await expect(page.getByRole("table")).toBeHidden();
  const cards = page.getByRole("list");
  await expect(cards.getByText("Lapsed Student")).toBeVisible();
  await expect(cards.getByText("Not paid")).toBeVisible();
});
