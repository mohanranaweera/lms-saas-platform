import { test, expect, type Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

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
  test("every tab is a real role=tab with the expected accessible names — no Financial summary/Sessions tab exists", async ({
    page,
  }) => {
    await gotoTeacherDetail(page);
    const tablist = page.getByRole("tablist", { name: "Teacher detail sections" });
    for (const label of ["Profile", "Assigned Courses", "Roster", "Attendance", "Exams", "Activity"]) {
      await expect(tablist.getByRole("tab", { name: label })).toBeVisible();
    }
    await expect(tablist.getByRole("tab", { name: /Financial/i })).toHaveCount(0);
    await expect(tablist.getByRole("tab", { name: /Sessions/i })).toHaveCount(0);
  });
});

test.describe("teacher detail — Assigned Courses tab", () => {
  test("empty state when the teacher has no assigned courses", async ({ page }) => {
    await gotoTeacherDetail(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));

    await page.getByRole("tab", { name: "Assigned Courses" }).click();
    await expect(page.getByText("No assigned courses")).toBeVisible();
  });

  test("populated: lists the teacher's own courses", async ({ page }) => {
    await gotoTeacherDetail(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([COURSE]));

    await page.getByRole("tab", { name: "Assigned Courses" }).click();
    await expect(page.getByRole("table").getByText("Intro to Biology")).toBeVisible();
  });

  test("a 403 renders the permission-denied state", async ({ page }) => {
    await gotoTeacherDetail(page);
    await mockJson(page, "**/v1/courses*", 403, apiError("FORBIDDEN", "You do not have permission to view courses."));

    await page.getByRole("tab", { name: "Assigned Courses" }).click();
    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
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
      apiSuccess([{ studentId: "s1", userId: "u1", name: "Ada Lovelace", email: "ada@example.test" }])
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

test.describe("teacher detail — Exams tab", () => {
  test("paginates with Previous/Next controls, matching the sibling Roster/Attendance tabs", async ({ page }) => {
    await gotoTeacherDetail(page);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([COURSE]));

    const page1Exam = {
      id: "exam-1",
      title: "Midterm",
      status: "PUBLISHED",
      scheduledStart: new Date().toISOString(),
    };
    const page2Exam = {
      id: "exam-2",
      title: "Final",
      status: "DRAFT",
      scheduledStart: new Date().toISOString(),
    };
    await page.route(`**/v1/exams/courses/${COURSE.id}/exams*`, async (route) => {
      const url = new URL(route.request().url());
      const requestedPage = Number(url.searchParams.get("page") ?? "0");
      const content = requestedPage === 0 ? [page1Exam] : [page2Exam];
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(
          apiPageSuccess(content, { page: requestedPage, size: 10, totalElements: 2, totalPages: 2 })
        ),
      });
    });

    await page.getByRole("tab", { name: "Exams" }).click();
    await expect(page.getByRole("table").getByText("Midterm")).toBeVisible();
    await expect(page.getByText("Page 1 of 2")).toBeVisible();
    await expect(page.getByRole("button", { name: "Previous" })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeEnabled();

    await page.getByRole("button", { name: "Next", exact: true }).click();
    await expect(page.getByRole("table").getByText("Final")).toBeVisible();
    await expect(page.getByText("Page 2 of 2")).toBeVisible();
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeDisabled();

    await page.getByRole("button", { name: "Previous" }).click();
    await expect(page.getByRole("table").getByText("Midterm")).toBeVisible();
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
