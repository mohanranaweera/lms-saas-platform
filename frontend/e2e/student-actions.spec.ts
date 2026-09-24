import { test, expect, type Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Student Detail Actions (Wave 3, PAR-03-05) — Activate/Deactivate, Enroll,
 * Revoke (covered in `student-detail-tabs.spec.ts`'s Enrollments tab specs),
 * Reset Password. No real backend runs in this environment.
 */

const ACTIVE_STUDENT = {
  id: "11111111-1111-1111-1111-111111111111",
  name: "Ada Lovelace",
  email: "ada@example-institute.test",
  roleCode: "STUDENT",
  status: "ACTIVE",
};

const SUSPENDED_STUDENT = { ...ACTIVE_STUDENT, id: "22222222-2222-2222-2222-222222222222", status: "SUSPENDED" };

async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

test.describe("student actions — activate/deactivate", () => {
  test("deactivating an active student updates the status badge without a page reload", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    let deactivated = false;
    await page.route(`**/v1/students/${ACTIVE_STUDENT.id}`, async (route) => {
      const current = deactivated ? { ...ACTIVE_STUDENT, status: "SUSPENDED" } : ACTIVE_STUDENT;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(current)) });
    });
    await page.route(`**/v1/students/${ACTIVE_STUDENT.id}/deactivate`, async (route) => {
      deactivated = true;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ ...ACTIVE_STUDENT, status: "SUSPENDED" })),
      });
    });

    await page.goto(`/tenant-admin/students/${ACTIVE_STUDENT.id}`);
    await expect(page.getByRole("button", { name: "Deactivate" })).toBeVisible();
    await page.getByRole("button", { name: "Deactivate" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Deactivate" }).click();

    // Wait for the dialog to finish closing before asserting on the toggle's
    // new label — its confirm button's own label flips in lockstep with the
    // toggle's derived state, so asserting mid-close-transition can
    // transiently see two "Activate"-labeled buttons (the dialog's own,
    // about to unmount, plus the now-updated page toggle).
    await expect(page.getByRole("alertdialog")).toBeHidden();
    await expect(page.getByText("Suspended", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("button", { name: "Activate" })).toBeVisible();
    // This app has no toast library — a brief, distinguishable
    // `role="status" aria-live="polite"` page-level notice confirms the
    // mutation succeeded, in addition to the badge updating in place.
    await expect(page.getByText(`${ACTIVE_STUDENT.name} was deactivated.`)).toBeVisible();
  });

  test("activating a suspended student succeeds", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, `**/v1/students/${SUSPENDED_STUDENT.id}`, 200, apiSuccess(SUSPENDED_STUDENT));
    await mockJson(
      page,
      `**/v1/students/${SUSPENDED_STUDENT.id}/activate`,
      200,
      apiSuccess({ ...SUSPENDED_STUDENT, status: "ACTIVE" })
    );

    await page.goto(`/tenant-admin/students/${SUSPENDED_STUDENT.id}`);
    await page.getByRole("button", { name: "Activate" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Activate" }).click();

    await expect(page.getByText("Active", { exact: true }).first()).toBeVisible();
  });

  test("a 403 on deactivate is surfaced inline, dialog stays open", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, `**/v1/students/${ACTIVE_STUDENT.id}`, 200, apiSuccess(ACTIVE_STUDENT));
    await mockJson(
      page,
      `**/v1/students/${ACTIVE_STUDENT.id}/deactivate`,
      403,
      apiError("FORBIDDEN", "You do not have permission to deactivate this student.")
    );

    await page.goto(`/tenant-admin/students/${ACTIVE_STUDENT.id}`);
    await page.getByRole("button", { name: "Deactivate" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Deactivate" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByText("You do not have permission to deactivate this student.")).toBeVisible();
    await expect(dialog).toBeVisible();
  });
});

test.describe("student actions — enroll", () => {
  test("enrolling requires a course and a reason before submitting", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, `**/v1/students/${ACTIVE_STUDENT.id}`, 200, apiSuccess(ACTIVE_STUDENT));
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([{ id: "course-1", teacherId: "t1", name: "Intro to Biology", slug: "intro-bio", category: "Science", subject: null, stream: null, grade: null, academicYear: null, description: null, price: 49.99, accessDurationDays: null, enrollmentRule: null, status: "PUBLIC", pricingModel: "ONE_TIME", archivedAt: null, createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(), resolvedAmount: 49.99, currency: "USD", requiresManualQuote: false }]));

    let enrollRequestMade = false;
    await page.route(`**/v1/students/${ACTIVE_STUDENT.id}/enroll`, async (route) => {
      enrollRequestMade = true;
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(null)) });
    });

    await page.goto(`/tenant-admin/students/${ACTIVE_STUDENT.id}`);
    await page.getByRole("button", { name: "Enroll" }).click();
    await expect(page.getByRole("dialog").getByRole("heading", { name: `Enroll ${ACTIVE_STUDENT.name}` })).toBeVisible();

    await page.getByRole("button", { name: "Enroll", exact: true }).last().click();
    await expect(page.getByText("Choose a course.")).toBeVisible();
    await expect(page.getByText("A reason is required.")).toBeVisible();
    expect(enrollRequestMade).toBe(false);
  });

  test("a successful enroll closes the sheet", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, `**/v1/students/${ACTIVE_STUDENT.id}`, 200, apiSuccess(ACTIVE_STUDENT));
    await mockJson(
      page,
      "**/v1/courses*",
      200,
      apiPageSuccess([
        {
          id: "course-1",
          teacherId: "t1",
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
        },
      ])
    );
    let enrollBody: unknown = null;
    await page.route(`**/v1/students/${ACTIVE_STUDENT.id}/enroll`, async (route) => {
      enrollBody = route.request().postDataJSON();
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(null)) });
    });
    await mockJson(page, `**/v1/students/${ACTIVE_STUDENT.id}/enrollments`, 200, apiSuccess([]));

    await page.goto(`/tenant-admin/students/${ACTIVE_STUDENT.id}`);
    await page.getByRole("button", { name: "Enroll" }).click();
    // The mandatory Reason field carries a visible "(required)" marker,
    // matching this codebase's existing optionality-labeling convention
    // (`(auth)/register/page.tsx`'s `RegisterField` marks only *optional*
    // fields — this mirrors that same micro-pattern).
    await expect(page.getByRole("dialog").getByText("Reason (required)")).toBeVisible();
    await expect(page.getByLabel("Reason")).toHaveAttribute("aria-required", "true");
    await page.getByLabel("Course").click();
    await page.getByRole("option", { name: "Intro to Biology" }).click();
    await page.getByLabel("Reason").fill("Scholarship grant approved by finance");
    await page.getByRole("button", { name: "Enroll", exact: true }).last().click();

    await expect(page.getByRole("dialog")).toBeHidden();
    expect(enrollBody).toEqual({ courseId: "course-1", reason: "Scholarship grant approved by finance" });
    // No toast library in this app — a brief, distinguishable
    // `role="status" aria-live="polite"` page-level notice confirms the
    // enroll succeeded.
    await expect(page.getByText(`${ACTIVE_STUDENT.name} was enrolled.`)).toBeVisible();
  });
});

test.describe("student actions — reset password", () => {
  test("resetting shows the one-time temporary password in a dismissible dialog", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, `**/v1/students/${ACTIVE_STUDENT.id}`, 200, apiSuccess(ACTIVE_STUDENT));
    await mockJson(
      page,
      `**/v1/students/${ACTIVE_STUDENT.id}/reset-password`,
      200,
      apiSuccess({ temporaryPassword: "Sup3rSecretTemp!" })
    );

    await page.goto(`/tenant-admin/students/${ACTIVE_STUDENT.id}`);
    await page.getByRole("button", { name: "Reset password" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Reset password" }).click();

    await expect(page.getByText("Sup3rSecretTemp!")).toBeVisible();
    await page.getByRole("button", { name: "Done" }).click();
    await expect(page.getByRole("alertdialog")).toBeHidden();
  });

  test("the temporary password is never persisted beyond the dialog — reopening prompts for a fresh reset", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, `**/v1/students/${ACTIVE_STUDENT.id}`, 200, apiSuccess(ACTIVE_STUDENT));
    await mockJson(
      page,
      `**/v1/students/${ACTIVE_STUDENT.id}/reset-password`,
      200,
      apiSuccess({ temporaryPassword: "FirstTempPass1!" })
    );

    await page.goto(`/tenant-admin/students/${ACTIVE_STUDENT.id}`);
    await page.getByRole("button", { name: "Reset password" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Reset password" }).click();
    await expect(page.getByText("FirstTempPass1!")).toBeVisible();
    await page.getByRole("button", { name: "Done" }).click();
    await expect(page.getByRole("alertdialog")).toBeHidden();

    await page.getByRole("button", { name: "Reset password" }).click();
    await expect(page.getByText("FirstTempPass1!")).toHaveCount(0);
    await expect(page.getByRole("alertdialog").getByRole("heading", { name: /Reset .* password\?/ })).toBeVisible();
  });

  test("an Escape press while the reset is in flight does not dismiss the dialog", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, `**/v1/students/${ACTIVE_STUDENT.id}`, 200, apiSuccess(ACTIVE_STUDENT));
    await page.route(`**/v1/students/${ACTIVE_STUDENT.id}/reset-password`, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 500));
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess({ temporaryPassword: "InFlightTemp1!" })),
      });
    });

    await page.goto(`/tenant-admin/students/${ACTIVE_STUDENT.id}`);
    await page.getByRole("button", { name: "Reset password" }).click();
    await page.getByRole("alertdialog").getByRole("button", { name: "Reset password" }).click();

    // The mutation is still in flight (500ms delay) — dismissing now, per
    // `create-student-sheet.tsx`/`enroll-student-sheet.tsx`'s identical
    // guard, must not close the dialog out from under the pending request.
    await expect(page.getByRole("button", { name: "Resetting…" })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByRole("alertdialog")).toBeVisible();

    await expect(page.getByText("InFlightTemp1!")).toBeVisible();
  });
});
