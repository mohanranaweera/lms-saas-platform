import { test, expect } from "@playwright/test";
import { apiSuccess, fakeJwt, mockJson, refreshResponseBody } from "./fixtures/auth-mocks";

/**
 * Coverage note: `EmptyState` (components/states/empty-state.tsx) is the only shared
 * state component actually wired into a live route so far — all four role dashboards
 * render it, which is what the tests below exercise end-to-end.
 *
 * `/student/dashboard` stopped being a static `EmptyState`-only placeholder as of
 * MVP-013 (Student Dashboard, SDASH-1), and `/teacher/dashboard` stopped being one
 * as of MVP-014 (Teacher Dashboard, TDASH-1) — both are now real data-driven
 * Overview pages. Their own loading/empty/error states are covered by
 * `student-dashboard.spec.ts` and `teacher-dashboard.spec.ts` respectively, instead
 * of this file; the remaining two role dashboards (Tenant Admin, Platform Admin)
 * remain the original static placeholder this file was written for.
 *
 * `LoadingState`, `ErrorState`, and `PermissionDeniedState` (components/states/) exist
 * as a shared library with their own accessibility attributes (`aria-busy`,
 * `aria-live="polite"`, `role="alert"`) but are not yet rendered from any page/route
 * covered by *this* file — no data-fetching module exists yet for
 * Tenant-Admin/Platform-Admin's own dashboards to trigger a real loading/error
 * state (see `src/lib/api/client.ts` and `error.ts`, which are unused infrastructure
 * for the same reason for those two). Writing a Playwright test against those
 * components today would require either modifying application source to add a
 * synthetic preview/demo route (out of scope for a test-only change) or asserting
 * against the component in isolation via a harness page, which would not prove
 * anything about real app behavior and would be misleading to report as "tested".
 * Deferring this: it should be added as part of the first module that actually
 * performs data fetching (React Query loading/error states) or the first
 * permission-gated route for each of those two, at which point this file's coverage
 * should be extended to visit that real page in each state.
 */
test.describe("empty state — contextual copy per role", () => {
  test("each still-placeholder dashboard's empty state has role-specific, non-generic copy", async ({
    page,
  }) => {
    // `(tenant-admin)` is wrapped in `RouteGuard` (MVP-007) — mock a
    // successful refresh so ensureAccessToken("tenant") resolves instead of
    // redirecting to /login.
    const tenantAdminToken = fakeJwt({ role: "TENANT_ADMIN" });
    await mockJson(
      page,
      "**/v1/auth/refresh",
      200,
      apiSuccess(refreshResponseBody(tenantAdminToken))
    );
    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByText("Tenant admin dashboard coming soon")).toBeVisible();

    await page.goto("/platform-admin/dashboard");
    await expect(page.getByText("Platform admin dashboard coming soon")).toBeVisible();
  });
});

test.describe("not-found state", () => {
  test("renders an accessible, actionable not-found page", async ({ page }) => {
    await page.goto("/no-such-route");
    const heading = page.getByRole("heading", { name: "Page not found" });
    await expect(heading).toBeVisible();
    await expect(page.getByRole("link", { name: "Back to home" })).toBeVisible();
  });
});
