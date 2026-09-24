import { test, expect, type Page } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Student Detail tabs (Wave 3, PAR-03-04) —
 * `/tenant-admin/students/[studentId]` — Profile / Enrollments / Payments /
 * Attendance / Exams / Activity. No real backend runs in this environment —
 * every endpoint is intercepted via `page.route()`.
 */

const STUDENT = {
  id: "11111111-1111-1111-1111-111111111111",
  name: "Ada Lovelace",
  email: "ada@example-institute.test",
  roleCode: "STUDENT",
  status: "ACTIVE",
};

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

async function gotoStudentDetail(page: Page, role = "TENANT_ADMIN") {
  await mockTenantSession(page, role);
  await mockJson(page, `**/v1/students/${STUDENT.id}`, 200, apiSuccess(STUDENT));
  await page.goto(`/tenant-admin/students/${STUDENT.id}`);
  await expect(page.getByRole("heading", { name: STUDENT.name })).toBeVisible();
}

test.describe("student detail tabs — tablist is keyboard/aria navigable", () => {
  test("every tab is a real role=tab with the expected accessible names", async ({ page }) => {
    await gotoStudentDetail(page);
    const tablist = page.getByRole("tablist", { name: "Student detail sections" });
    await expect(tablist).toBeVisible();
    for (const label of ["Profile", "Enrollments", "Payments", "Attendance", "Exams", "Activity"]) {
      await expect(tablist.getByRole("tab", { name: label })).toBeVisible();
    }
  });
});

test.describe("student detail — Enrollments tab", () => {
  test("loading state, then an empty state with no enrollments", async ({ page }) => {
    await gotoStudentDetail(page);
    await page.route(`**/v1/students/${STUDENT.id}/enrollments`, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 300));
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
    });

    await page.getByRole("tab", { name: "Enrollments" }).click();
    await expect(page.getByText("Loading enrollment history…")).toBeVisible();
    await expect(page.getByText("No enrollments yet")).toBeVisible();
  });

  test("an error state with retry", async ({ page }) => {
    await gotoStudentDetail(page);
    let requestCount = 0;
    await page.route(`**/v1/students/${STUDENT.id}/enrollments`, async (route) => {
      requestCount += 1;
      if (requestCount <= 2) {
        await route.fulfill({
          status: 500,
          contentType: "application/json",
          body: JSON.stringify({
            success: false,
            data: null,
            error: { code: "INTERNAL_ERROR", message: "Something went wrong.", fieldErrors: [] },
            timestamp: new Date().toISOString(),
            traceId: "test-trace-id",
          }),
        });
        return;
      }
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([])) });
    });

    await page.getByRole("tab", { name: "Enrollments" }).click();
    await expect(page.getByRole("alert").filter({ hasText: "Something went wrong." })).toBeVisible();
    await page.getByRole("button", { name: "Try again" }).click();
    await expect(page.getByText("No enrollments yet")).toBeVisible();
  });

  test("a permission-denied (403) state", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(
      page,
      `**/v1/students/${STUDENT.id}/enrollments`,
      403,
      apiError("FORBIDDEN", "You do not have permission to view enrollments.")
    );

    await page.getByRole("tab", { name: "Enrollments" }).click();
    await expect(page.getByText("You don't have permission to view this.")).toBeVisible();
  });

  test("populated: shows a current enrollment with a Revoke action for a manager role", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(
      page,
      `**/v1/students/${STUDENT.id}/enrollments`,
      200,
      apiSuccess([
        {
          enrollmentId: "e1",
          courseId: "c1234567-89ab-cdef-0123-456789abcdef",
          current: true,
          activatedAt: new Date().toISOString(),
          accessExpiresAt: null,
          supersededAt: null,
          revokedAt: null,
          revokeReason: null,
          reactivatedFromEnrollmentId: null,
        },
      ])
    );

    await page.getByRole("tab", { name: "Enrollments" }).click();
    await expect(page.getByText("Current", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: /^Revoke enrollment/ })).toBeVisible();
  });

  test("the Revoke dialog's Reason field is marked required, and a successful revoke shows a distinguishable notice", async ({
    page,
  }) => {
    await gotoStudentDetail(page);
    const courseId = "c1234567-89ab-cdef-0123-456789abcdef";
    let revoked = false;
    await page.route(`**/v1/students/${STUDENT.id}/enrollments`, async (route) => {
      const entry = {
        enrollmentId: "e1",
        courseId,
        current: !revoked,
        activatedAt: new Date().toISOString(),
        accessExpiresAt: null,
        supersededAt: null,
        revokedAt: revoked ? new Date().toISOString() : null,
        revokeReason: revoked ? "No longer enrolled" : null,
        reactivatedFromEnrollmentId: null,
      };
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess([entry])) });
    });
    await page.route(`**/v1/enrollments/e1/revoke`, async (route) => {
      revoked = true;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(null)) });
    });

    await page.getByRole("tab", { name: "Enrollments" }).click();
    await page.getByRole("button", { name: /^Revoke enrollment/ }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    // Mandatory field — the label carries a visible "(required)" marker
    // matching this codebase's existing optionality-labeling convention
    // (`(auth)/register/page.tsx`'s `RegisterField` marks only *optional*
    // fields; this mirrors that same micro-pattern rather than inventing a
    // new asterisk convention).
    await expect(dialog.getByText("Reason", { exact: false })).toBeVisible();
    await expect(dialog.getByLabel(/Reason/)).toHaveAttribute("aria-required", "true");

    await dialog.getByLabel(/Reason/).fill("No longer enrolled");
    await dialog.getByRole("button", { name: "Revoke", exact: true }).click();

    await expect(dialog).toBeHidden();
    // No toast library in this app — a brief, distinguishable
    // `role="status" aria-live="polite"` page-level notice confirms the
    // mutation succeeded, mirroring `students/page.tsx#createdNotice`.
    await expect(page.getByText(`Enrollment in Course #${courseId.slice(0, 8)} was revoked.`)).toBeVisible();
  });
});

test.describe("student detail — Payments tab", () => {
  test("empty state, and a link to Reactivation Approvals for an eligible role", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(page, `**/v1/students/${STUDENT.id}/ledger`, 200, apiSuccess([]));

    await page.getByRole("tab", { name: "Payments" }).click();
    await expect(page.getByText("No payments yet")).toBeVisible();
    await expect(page.getByRole("link", { name: "Go to Reactivation Approvals" })).toBeVisible();
  });

  test("populated: renders a ledger entry", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(
      page,
      `**/v1/students/${STUDENT.id}/ledger`,
      200,
      apiSuccess([
        {
          id: "l1",
          orderId: "o1",
          paymentId: "p1",
          entryType: "PAYMENT_CONFIRMED",
          amount: 49.99,
          reversesEntryId: null,
          createdAt: new Date().toISOString(),
        },
      ])
    );

    await page.getByRole("tab", { name: "Payments" }).click();
    await expect(page.getByRole("table").getByText("PAYMENT_CONFIRMED")).toBeVisible();
  });
});

test.describe("student detail — Attendance tab", () => {
  test("empty state", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(page, `**/v1/attendance/students/${STUDENT.id}/report*`, 200, apiPageSuccess([]));

    await page.getByRole("tab", { name: "Attendance" }).click();
    await expect(page.getByText("No attendance recorded yet")).toBeVisible();
  });

  test("populated with pagination controls", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(
      page,
      `**/v1/attendance/students/${STUDENT.id}/report*`,
      200,
      apiPageSuccess(
        [
          {
            id: "a1",
            courseId: "c1",
            sessionId: "s1",
            studentId: STUDENT.id,
            status: "PRESENT",
            markedBy: "teacher-1",
            markedAt: new Date().toISOString(),
            createdAt: new Date().toISOString(),
            updatedAt: new Date().toISOString(),
          },
        ],
        { totalPages: 2, totalElements: 11 }
      )
    );

    await page.getByRole("tab", { name: "Attendance" }).click();
    await expect(page.getByRole("table").getByText("PRESENT")).toBeVisible();
    await expect(page.getByText("Page 1 of 2")).toBeVisible();
    await expect(page.getByRole("button", { name: "Previous" })).toBeDisabled();
    await expect(page.getByRole("button", { name: "Next", exact: true })).toBeEnabled();
  });
});

test.describe("student detail — Exams tab", () => {
  test("empty state", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(page, `**/v1/exams/students/${STUDENT.id}/attempts*`, 200, apiPageSuccess([]));

    await page.getByRole("tab", { name: "Exams" }).click();
    await expect(page.getByText("No exam attempts yet")).toBeVisible();
  });

  test("populated", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(
      page,
      `**/v1/exams/students/${STUDENT.id}/attempts*`,
      200,
      apiPageSuccess([
        {
          id: "attempt-1",
          examId: "exam-1",
          studentId: STUDENT.id,
          startedAt: new Date().toISOString(),
          submittedAt: null,
          status: "IN_PROGRESS",
        },
      ])
    );

    await page.getByRole("tab", { name: "Exams" }).click();
    await expect(page.getByRole("table").getByText("IN_PROGRESS")).toBeVisible();
  });
});

test.describe("student detail — Activity tab", () => {
  test("empty state", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(page, `**/v1/students/${STUDENT.id}/activity*`, 200, apiPageSuccess([]));

    await page.getByRole("tab", { name: "Activity" }).click();
    await expect(page.getByText("No activity recorded yet")).toBeVisible();
  });

  test("populated: renders an activity entry with reason and metadata", async ({ page }) => {
    await gotoStudentDetail(page);
    await mockJson(
      page,
      `**/v1/students/${STUDENT.id}/activity*`,
      200,
      apiPageSuccess([
        {
          id: "activity-1",
          actorId: "staff-1",
          actorDisplayName: "Jordan Admin",
          action: "student.enrolled",
          reason: "Scholarship grant",
          metadata: { courseId: "c1" },
          occurredAt: new Date().toISOString(),
        },
      ])
    );

    await page.getByRole("tab", { name: "Activity" }).click();
    await expect(page.getByText("student.enrolled")).toBeVisible();
    await expect(page.getByText("By Jordan Admin")).toBeVisible();
    await expect(page.getByText("Reason: Scholarship grant")).toBeVisible();
  });
});
