import { test, expect } from "@playwright/test";

/**
 * Coverage note: `EmptyState` (components/states/empty-state.tsx) is a shared
 * state component still wired into the one remaining static placeholder role
 * dashboard (Platform Admin), which is what the test below exercises
 * end-to-end.
 *
 * `/student/dashboard` stopped being a static `EmptyState`-only placeholder as of
 * MVP-013 (Student Dashboard, SDASH-1), `/teacher/dashboard` stopped being one
 * as of MVP-014 (Teacher Dashboard, TDASH-1), and `/tenant-admin/dashboard`
 * stopped being one as of MVP-015 (Tenant Admin Dashboard, TADASH-1) — all
 * three are now real data-driven Overview pages. Their own loading/empty/error
 * states are covered by `student-dashboard.spec.ts`, `teacher-dashboard.spec.ts`,
 * and `tenant-admin-dashboard.spec.ts` respectively, instead of this file; the
 * remaining role dashboard (Platform Admin) remains the original static
 * placeholder this file was written for.
 *
 * `LoadingState`, `ErrorState`, and `PermissionDeniedState` (components/states/) exist
 * as a shared library with their own accessibility attributes (`aria-busy`,
 * `aria-live="polite"`, `role="alert"`) but are not yet rendered from any page/route
 * covered by *this* file — no data-fetching module exists yet for
 * Platform-Admin's own dashboard to trigger a real loading/error
 * state (see `src/lib/api/client.ts` and `error.ts`, which are unused infrastructure
 * for the same reason for it). Writing a Playwright test against those
 * components today would require either modifying application source to add a
 * synthetic preview/demo route (out of scope for a test-only change) or asserting
 * against the component in isolation via a harness page, which would not prove
 * anything about real app behavior and would be misleading to report as "tested".
 * Deferring this: it should be added as part of the first module that actually
 * performs data fetching (React Query loading/error states) or the first
 * permission-gated route for Platform Admin, at which point this file's coverage
 * should be extended to visit that real page in each state.
 */
test.describe("empty state — contextual copy per role", () => {
  test("the still-placeholder Platform Admin dashboard's empty state has role-specific, non-generic copy", async ({
    page,
  }) => {
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
