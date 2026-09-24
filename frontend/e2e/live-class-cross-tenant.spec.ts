import { test, expect } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import { courseResponseBody, mockTenantSession, TEACHER_ID } from "./fixtures/class-session-mocks";

/**
 * Wave 4 cross-tenant negative coverage: a Tenant A caller addressing a
 * Tenant B `class_session` id gets the backend's real anti-enumeration `404`
 * (`LiveClassAccessGuard`'s Student branch; a cross-tenant Teacher/staff
 * lookup also resolves to "not found" since `ClassSessionRepository` is
 * tenant-scoped — the row simply doesn't exist in this tenant's view), which
 * this UI must render as the ordinary generic error/not-found state — never
 * `PermissionDeniedState`, and never a distinguishable message that would let
 * a caller tell "wrong tenant" apart from "never existed".
 */

const FOREIGN_SESSION_ID = "ffffffff-ffff-ffff-ffff-ffffffffffff";

test.describe("Cross-tenant class session access", () => {
  test("Teacher: a foreign-tenant session id 404s as the generic error state, never PermissionDeniedState", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER", TEACHER_ID);
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([courseResponseBody()]));
    await mockJson(
      page,
      `**/v1/class-sessions/${FOREIGN_SESSION_ID}`,
      404,
      apiError("NOT_FOUND", "Class session not found")
    );

    await page.goto(`/teacher/live-classes/${FOREIGN_SESSION_ID}`);

    const errorAlert = page.getByRole("alert").filter({ hasText: "Class session not found" });
    await expect(errorAlert).toBeVisible();
    await expect(page.getByText("You don't have permission")).toHaveCount(0);
  });

  test("Tenant Admin oversight detail: a foreign-tenant session id 404s as the generic error state", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      `**/v1/class-sessions/${FOREIGN_SESSION_ID}`,
      404,
      apiError("NOT_FOUND", "Class session not found")
    );

    await page.goto(`/tenant-admin/live-classes/${FOREIGN_SESSION_ID}`);

    const errorAlert = page.getByRole("alert").filter({ hasText: "Class session not found" });
    await expect(errorAlert).toBeVisible();
    await expect(page.getByText("You don't have permission")).toHaveCount(0);
  });

  test("Student: the list read itself is the only access surface — a foreign-tenant/not-enrolled session is simply absent from the list, not reachable by any direct URL in this UI", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    await mockJson(page, "**/v1/enrollments/my/courses", 200, apiSuccess([]));
    // The backend's own entitlement filter on `GET /v1/class-sessions` is
    // what actually prevents this — simulated here as an empty list, exactly
    // what a Student with no ACTIVE enrollment in the foreign session's
    // course would receive regardless of how many other-tenant sessions
    // exist server-side.
    await mockJson(page, "**/v1/class-sessions*", 200, apiSuccess([]));

    await page.goto("/student/live-classes");

    await expect(page.getByText("No upcoming live classes")).toBeVisible();
    // Scoped to `main` — Next.js's own always-present route-announcer div
    // carries `role="alert"` outside the app's rendered content.
    await expect(page.getByRole("main").getByRole("alert")).toHaveCount(0);
  });

  test("a real backend 403 (e.g. a staff role without LIVE_CLASSES/VIEW) still renders PermissionDeniedState, proving the two paths are correctly distinguished", async ({
    page,
  }) => {
    await mockTenantSession(page, "FINANCE_STAFF");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));
    await mockJson(page, "**/v1/teachers*", 200, apiSuccess([]));
    await page.route("**/v1/class-sessions*", async (route) => {
      await fulfillJson(route, 403, apiError("FORBIDDEN", "You do not have permission to view live classes."));
    });

    await page.goto("/tenant-admin/live-classes");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
  });
});
