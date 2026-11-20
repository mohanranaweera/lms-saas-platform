import { test, expect, type Page } from "@playwright/test";
import { apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Teacher Detail tabs (Wave 3, PAR-04-03) —
 * `/tenant-admin/teachers/[teacherId]` — Profile / Assigned Courses / Roster
 * / Attendance / Exams / Activity. No real backend runs in this environment.
 */

const TEACHER = {
  id: "teacher-detail-tabs-1",
  name: "Kavindi Fernando",
  email: "kavindi@example.test",
  approvalStatus: "APPROVED" as const,
  accountStatus: "ACTIVE" as const,
  approvedBy: "admin-1",
  approvedAt: new Date().toISOString(),
};

const COURSE = {
  id: "course-1",
  teacherId: TEACHER.id,
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

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

async function gotoTeacherDetail(page: Page) {
  await mockTenantSession(page, "TENANT_ADMIN");
  await mockJson(page, `**/v1/teachers/${TEACHER.id}`, 200, apiSuccess(TEACHER));
  await page.goto(`/tenant-admin/teachers/${TEACHER.id}`);
  await expect(page.getByText(TEACHER.name).first()).toBeVisible();
}

test.describe("teacher detail tabs — tablist", () => {
  // Teacher management parity (fd7b85c) cut the tabs to Profile / Roster / Activity.
  test("every tab is a real role=tab with the expected accessible names — no Financial summary/Sessions tab exists", async ({
    page,
  }) => {
    await gotoTeacherDetail(page);
    const tablist = page.getByRole("tablist", { name: "Teacher detail sections" });
    for (const label of ["Profile", "Roster", "Activity"]) {
      await expect(tablist.getByRole("tab", { name: label })).toBeVisible();
    }
    await expect(tablist.getByRole("tab", { name: /Financial/i })).toHaveCount(0);
    await expect(tablist.getByRole("tab", { name: /Sessions/i })).toHaveCount(0);
  });
});

test.describe("teacher detail — Roster tab", () => {
  test("picks the teacher's first course and shows its roster", async ({ page }) => {
    await gotoTeacherDetail(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([COURSE]));
    await mockJson(
      page,
      `**/v1/courses/${COURSE.id}/roster`,
      200,
      apiSuccess([{ studentId: "s1", userId: "u1", name: "Ada Lovelace", email: "ada@example.test", accessStatus: "PAID" }])
    );

    await page.getByRole("tab", { name: "Roster" }).click();
    await expect(page.getByRole("table").getByText("Ada Lovelace")).toBeVisible();
  });

  test("no assigned courses means no roster to show", async ({ page }) => {
    await gotoTeacherDetail(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));

    await page.getByRole("tab", { name: "Roster" }).click();
    await expect(
      page.getByText("This teacher has no courses assigned to them, so there is no roster to show.")
    ).toBeVisible();
  });
});

test.describe("teacher detail — Activity tab", () => {
  test("empty state", async ({ page }) => {
    await gotoTeacherDetail(page);
    await mockJson(page, `**/v1/teachers/${TEACHER.id}/activity*`, 200, apiPageSuccess([]));

    await page.getByRole("tab", { name: "Activity" }).click();
    await expect(page.getByText("No activity recorded yet")).toBeVisible();
  });

  test("populated: renders a suspend/reactivate audit entry", async ({ page }) => {
    await gotoTeacherDetail(page);
    await mockJson(
      page,
      `**/v1/teachers/${TEACHER.id}/activity*`,
      200,
      apiPageSuccess([
        {
          id: "activity-1",
          actorId: "staff-1",
          actorDisplayName: "Jordan Admin",
          action: "teacher.suspended",
          reason: null,
          metadata: null,
          occurredAt: new Date().toISOString(),
        },
      ])
    );

    await page.getByRole("tab", { name: "Activity" }).click();
    await expect(page.getByText("teacher.suspended")).toBeVisible();
    await expect(page.getByText("By Jordan Admin")).toBeVisible();
  });
});
