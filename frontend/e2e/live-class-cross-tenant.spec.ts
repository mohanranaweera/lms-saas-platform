import { test, expect } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess, fulfillJson, mockJson } from "./fixtures/auth-mocks";
import {
  COURSE_ID,
  courseResponseBody,
  mockTenantSession,
  TEACHER_ID,
} from "./fixtures/class-session-mocks";
import { mockKlassStudentCourse } from "./fixtures/klass/student-course-mocks";

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
const FOREIGN_COURSE_ID = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee";

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

  // KLASS (ADR-028, plan §4.3): the student course page's live list is now
  // `GET /courses/{id}/live-classes`, evaluated server-side for this student
  // and tenant; joining always goes through a server launch. The intent is
  // unchanged and extended to the new surfaces: nothing foreign is listed,
  // a foreign course id is the generic "not available" state (never a
  // permission message that confirms existence), and a foreign lesson id
  // launched directly is refused as "not available".
  test("Student: the server-scoped live list is the only access surface — a foreign-tenant/not-enrolled class is simply absent, and foreign ids are refused generically", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    // The backend's own evaluation of `GET /courses/{id}/live-classes` is what
    // actually prevents this — simulated as the empty list it returns.
    await mockKlassStudentCourse(page, { liveClasses: [] });
    await mockJson(page, "**/v1/class-sessions*", 200, apiSuccess([]));

    await page.goto(`/student/courses/${COURSE_ID}`);
    await page.getByTestId("course-tab-live").click();

    await expect(page.getByTestId("live-classes-empty")).toContainText("No upcoming live classes");
    await expect(page.getByTestId("live-class-row")).toHaveCount(0);
    // Scoped to `main` — Next.js's own always-present route-announcer div
    // carries `role="alert"` outside the app's rendered content; the page's
    // always-present EMPTY toast alert region does not count as an error.
    await expect(page.getByRole("main").getByRole("alert").filter({ hasText: /\S/ })).toHaveCount(0);
  });

  test("Student: a foreign-tenant course id renders the generic 'not available' state, never PermissionDeniedState", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT", "student-1");
    await mockKlassStudentCourse(page, { courseId: FOREIGN_COURSE_ID, course: null });

    await page.goto(`/student/courses/${FOREIGN_COURSE_ID}`);

    await expect(page.getByTestId("course-detail-unavailable")).toContainText("This course isn't available");
    await expect(page.getByText("You don't have permission")).toHaveCount(0);
    await expect(page.getByTestId("live-classes-panel")).toHaveCount(0);
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
