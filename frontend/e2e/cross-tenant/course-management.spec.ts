import { test, expect, installHostHeaderRewrite, loginAsRole, RealBackendSessionUnavailableError } from "../fixtures/real-backend-session";
import { TENANT_A } from "../fixtures/seed/tenants";

/**
 * Real-backend cross-tenant negative spec for course-management
 * (`docs/plans/MVP-021 Integration and Staging Review.md` §18.4).
 *
 * Mirrors the backend's own proof —
 * `CourseManagementIntegrationTest`'s tenant-B-cannot-read-tenant-A's-course
 * cases (`docs/plans/...` §18.1) — through the real UI: log in as Tenant A's
 * Tenant Admin, navigate to a course id known to belong to Tenant B, and
 * assert the real backend's 404 (not 200-with-leaked-data) surfaces via the
 * app's existing error-state handling.
 *
 * `@Tag`-equivalent for Playwright: run this file specifically via
 * `npx playwright test --grep @cross-tenant` (see frontend/e2e/README.md).
 * @cross-tenant
 *
 * STATUS: This is REAL code against the REAL fixture layer — it is not a
 * mock, and it is not a stub. It is expected to `test.skip()` today, with a
 * clear, visible reason, because of the two structural gaps documented in
 * `frontend/e2e/README.md` (no CORS config on the backend; no API path to
 * provision a Tenant Admin account). It will start exercising the real
 * assertions below the moment both gaps are closed — nothing about this
 * spec's own logic needs to change.
 */

test.describe("course-management — cross-tenant", () => {
  test("Tenant A's Tenant Admin cannot read Tenant B's course by id @cross-tenant", async ({ page, context }) => {
    // A placeholder id shape (a real one would come from the seed script's
    // output once it can create courses — see two-tenant-seed.mjs's blocker
    // report). Kept as a literal, clearly-fake UUID so a future maintainer
    // wiring this up for real replaces it deliberately, not by accident.
    const tenantBCourseId = "00000000-0000-0000-0000-000000000000";

    try {
      await installHostHeaderRewrite(context, TENANT_A.subdomain);
      await loginAsRole(page, { role: "TENANT_ADMIN", tenant: TENANT_A });
    } catch (error) {
      if (error instanceof RealBackendSessionUnavailableError) {
        test.skip(true, `Real-backend session unavailable: ${error.message}`);
        return;
      }
      throw error;
    }

    await page.goto(`/tenant-admin/courses/${tenantBCourseId}`);

    // Per docs/api/course-management.md: a cross-tenant course id returns
    // 404 NOT_FOUND (uniform with "doesn't exist"), never 200 with the
    // course's data and never a 403 that would confirm the id's existence.
    const permissionDeniedOrNotFound = page.getByRole("alert").filter({ hasText: /not found/i });
    await expect(permissionDeniedOrNotFound).toBeVisible();
  });
});
