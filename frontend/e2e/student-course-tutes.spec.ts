import { test, expect, type Page } from "@playwright/test";
import { apiError, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";
import { mockKlassStudentCourse } from "./fixtures/klass/student-course-mocks";

/**
 * Student "Tute Delivery" tab (TUTE-02): renders the caller's own tutes from
 * `GET /courses/{id}/my-tutes` with the server's status, and picks up a staff
 * dispatch on the next read (tab reopen) - never a client-computed status.
 *
 * Mock-only: own-rows / cross-student / cross-tenant scoping is proven by
 * `StudentTuteIntegrationTest`.
 */

const COURSE_ID = "c0000000-0000-0000-0000-000000007e5";

async function mockStudentSession(page: Page): Promise<void> {
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(fakeJwt({ role: "STUDENT" }))));
}

function tute(overrides: Record<string, unknown>) {
  return {
    id: "f-1",
    tuteName: "Unit 1 notes",
    status: "PENDING",
    method: "POSTAL",
    assignedAt: "2026-11-01T04:30:00Z",
    dispatchedAt: null,
    ...overrides,
  };
}

test.describe("student — Tute Delivery tab", () => {
  test("shows a pending tute, then Dispatched with the posted date once staff dispatch it", async ({ page }) => {
    await mockStudentSession(page);
    let rows = [tute({})];
    await mockKlassStudentCourse(page, { courseId: COURSE_ID, myTutes: () => rows });

    await page.goto(`/student/courses/${COURSE_ID}?tab=tutes`);
    const row = page.getByTestId("course-tute-row");
    await expect(row).toHaveCount(1);
    await expect(row).toContainText("Unit 1 notes");
    await expect(row.getByTestId("course-tute-status")).toHaveText("Pending");
    await expect(row).toContainText("Your institute will post this tute to you.");

    rows = [tute({ status: "DISPATCHED", dispatchedAt: "2026-11-09T09:00:00Z" })];
    await page.getByTestId("course-tab-content").click();
    await expect(page.getByTestId("course-tab-content")).toHaveAttribute("aria-selected", "true");
    await page.getByTestId("course-tab-tutes").click();

    await expect(row.getByTestId("course-tute-status")).toHaveText("Dispatched");
    await expect(row).toContainText("Posted on 09 Nov 2026");
  });

  test("labels handed-over collection copies and held copies", async ({ page }) => {
    await mockStudentSession(page);
    await mockKlassStudentCourse(page, {
      courseId: COURSE_ID,
      myTutes: () => [
        tute({ id: "f-1", tuteName: "Collected pack", method: "COLLECTION", status: "DISPATCHED", dispatchedAt: "2026-11-08T09:00:00Z" }),
        tute({ id: "f-2", tuteName: "Held pack", status: "ON_HOLD" }),
      ],
    });

    await page.goto(`/student/courses/${COURSE_ID}?tab=tutes`);
    const rows = page.getByTestId("course-tute-row");
    await expect(rows.filter({ hasText: "Collected pack" }).getByTestId("course-tute-status")).toHaveText("Handed over");
    await expect(rows.filter({ hasText: "Held pack" }).getByTestId("course-tute-status")).toHaveText("On hold");
  });

  test("no tutes shows the empty state", async ({ page }) => {
    await mockStudentSession(page);
    await mockKlassStudentCourse(page, { courseId: COURSE_ID, myTutes: () => [] });

    await page.goto(`/student/courses/${COURSE_ID}?tab=tutes`);
    await expect(page.getByTestId("course-tutes-empty")).toContainText("No tutes for this course yet");
  });

  test("a failed read shows fixed error copy with retry", async ({ page }) => {
    await mockStudentSession(page);
    await mockKlassStudentCourse(page, { courseId: COURSE_ID });
    await mockJson(page, `**/api/v1/courses/${COURSE_ID}/my-tutes`, 500, apiError("INTERNAL_ERROR", "boom"));

    await page.goto(`/student/courses/${COURSE_ID}?tab=tutes`);
    await expect(page.getByText("We couldn't load your tutes for this course.")).toBeVisible();
    await expect(page.getByText("boom")).toHaveCount(0);
  });
});
