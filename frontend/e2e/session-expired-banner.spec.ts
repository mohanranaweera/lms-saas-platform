import { test, expect } from "@playwright/test";

/**
 * `route-groups.spec.ts` already asserts the session-expired banner *text* is
 * visible when `/login` is visited with `?reason=session_expired`. This file
 * adds the accessibility contract that spec doesn't check: the banner is a
 * `role="status"` (non-error, polite) region — not `role="alert"` — per
 * `components/auth/login-form.tsx`, which explicitly overrides `Alert`'s
 * `role="alert"` default to `role="status"` for this informational banner.
 * Also confirms the banner is absent on a plain `/login` visit, i.e. its
 * presence is driven only by the query param, not always rendered.
 */
test.describe("session-expired interstitial", () => {
  test("shows a role=status banner with the expected copy when redirected with reason=session_expired", async ({
    page,
  }) => {
    await page.goto("/login?reason=session_expired");

    const banner = page.getByRole("status").filter({ hasText: "Your session has expired" });
    await expect(banner).toBeVisible();
    await expect(banner).toContainText("Sign in again to continue.");
  });

  test("does not render the banner on a plain /login visit", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByText("Your session has expired")).toHaveCount(0);
    await expect(page.getByRole("status").filter({ hasText: "Your session has expired" })).toHaveCount(
      0
    );
  });
});
