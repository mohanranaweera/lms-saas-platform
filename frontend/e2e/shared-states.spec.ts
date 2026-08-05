import { test, expect } from "@playwright/test";

/**
 * Coverage note: `EmptyState` (components/states/empty-state.tsx) is the only shared
 * state component actually wired into a live route so far — all four role dashboards
 * render it, which is what the tests below exercise end-to-end.
 *
 * `LoadingState`, `ErrorState`, and `PermissionDeniedState` (components/states/) exist
 * as a shared library with their own accessibility attributes (`aria-busy`,
 * `aria-live="polite"`, `role="alert"`) but are not yet rendered from any page/route —
 * no data-fetching module exists yet to trigger a real loading/error state, and no
 * permission-gated route exists yet to trigger a real permission-denied state (see
 * `src/lib/api/client.ts` and `error.ts`, which are unused infrastructure for the same
 * reason). Writing a Playwright test against those components today would require
 * either modifying application source to add a synthetic preview/demo route (out of
 * scope for a test-only change) or asserting against the component in isolation via a
 * harness page, which would not prove anything about real app behavior and would be
 * misleading to report as "tested". Deferring this: it should be added as part of the
 * first module that actually performs data fetching (React Query loading/error
 * states) or the first permission-gated route, at which point this file's coverage
 * should be extended to visit that real page in each state.
 */
test.describe("empty state — contextual copy per role", () => {
  test("each dashboard's empty state has role-specific, non-generic copy", async ({
    page,
  }) => {
    await page.goto("/student/dashboard");
    await expect(page.getByText("Student dashboard coming soon")).toBeVisible();

    await page.goto("/teacher/dashboard");
    await expect(page.getByText("Teacher dashboard coming soon")).toBeVisible();

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
