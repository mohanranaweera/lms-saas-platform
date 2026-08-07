import { test, expect } from "@playwright/test";
import {
  apiError,
  apiSuccess,
  fakeJwt,
  loginResponseBody,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * Silent-refresh coverage for `AuthProvider`'s `ensureAccessToken` /
 * `authorizedFetch` (lib/auth/auth-context.tsx). There is no protected,
 * data-fetching page in this module yet to drive `authorizedFetch` from
 * (see that file's own doc comment, and `shared-states.spec.ts`'s coverage
 * note) — the only real caller in the shipped app is `logout()`
 * (`LogoutControl` -> `useAuth().logout`), so every scenario below is
 * exercised through the logout button on a role dashboard. Student/Teacher
 * portals are used (no confirmation dialog in the way) since the mechanics
 * under test are the token refresh/retry, not the confirmation UI (see
 * `logout.spec.ts` for that).
 *
 * Dashboards have no route guard yet (confirmed via `route-groups.spec.ts`,
 * which navigates to them directly with no prior login) — that's what makes
 * it possible to reach the "no cached token" `ensureAccessToken` path
 * (scenario B) without a real login first.
 */

test.describe("silent refresh — cached token, protected call rejected then retried", () => {
  test("a 401 SESSION_REVOKED on the protected call triggers exactly one refresh, then a successful retry", async ({
    page,
  }) => {
    // Establish a real in-memory session first, so `ensureAccessToken` has a
    // cached token and takes the "call now, refresh-and-retry on 401" branch
    // in `authorizedFetch` rather than the "no token yet" branch.
    const initialToken = fakeJwt({ role: "STUDENT" });
    await mockJson(page, "**/v1/auth/login", 200, apiSuccess(loginResponseBody(initialToken)));
    await page.goto("/login");
    await page.getByLabel("Email").fill("student@example.com");
    await page.getByLabel("Password", { exact: true }).fill("correct-horse-battery-staple");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page).toHaveURL(/\/student\/dashboard$/);

    let refreshCallCount = 0;
    const refreshedToken = fakeJwt({ role: "STUDENT", session_id: "session-2" });
    await page.route("**/v1/auth/refresh", async (route) => {
      refreshCallCount += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(refreshResponseBody(refreshedToken))),
      });
    });

    let logoutCallCount = 0;
    const logoutAuthHeaders: Array<string | undefined> = [];
    await page.route("**/v1/auth/logout", async (route) => {
      logoutCallCount += 1;
      logoutAuthHeaders.push(route.request().headers()["authorization"]);
      if (logoutCallCount === 1) {
        await route.fulfill({
          status: 401,
          contentType: "application/json",
          body: JSON.stringify(apiError("SESSION_REVOKED", "Session has been revoked")),
        });
        return;
      }
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(null)),
      });
    });

    // Student portal: logout has no confirmation step (see logout.spec.ts).
    await page.getByRole("button", { name: "Sign out" }).click();

    await expect(page).toHaveURL(/\/login$/);

    expect(refreshCallCount).toBe(1);
    expect(logoutCallCount).toBe(2);
    expect(logoutAuthHeaders[0]).toBe(`Bearer ${initialToken}`);
    // The retried call must carry the newly rotated token, not the stale one.
    expect(logoutAuthHeaders[1]).toBe(`Bearer ${refreshedToken}`);
  });
});

test.describe("silent refresh — no cached token", () => {
  test("a fresh session with no in-memory token refreshes once before making the protected call", async ({
    page,
  }) => {
    let refreshCallCount = 0;
    const refreshedToken = fakeJwt({ role: "TEACHER" });
    await page.route("**/v1/auth/refresh", async (route) => {
      refreshCallCount += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(refreshResponseBody(refreshedToken))),
      });
    });

    let logoutCallCount = 0;
    let logoutAuthHeader: string | undefined;
    await page.route("**/v1/auth/logout", async (route) => {
      logoutCallCount += 1;
      logoutAuthHeader = route.request().headers()["authorization"];
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(null)),
      });
    });

    // No prior login in this test — the access token is in-memory only and a
    // fresh page load never had one to begin with.
    await page.goto("/teacher/dashboard");
    await page.getByRole("button", { name: "Sign out" }).click();

    await expect(page).toHaveURL(/\/login$/);

    expect(refreshCallCount).toBe(1);
    expect(logoutCallCount).toBe(1);
    expect(logoutAuthHeader).toBe(`Bearer ${refreshedToken}`);
  });
});

test.describe("silent refresh — refresh itself fails", () => {
  test("a failed refresh surfaces an alert instead of silently redirecting", async ({ page }) => {
    // `ensureAccessToken`'s doc comment (lib/auth/auth-context.tsx) says
    // callers are responsible for redirecting to login with
    // `?reason=session_expired` when refresh fails. The only real caller in
    // this module is `logout()`, which propagates the failure rather than
    // swallowing it; `LogoutControl` surfaces it as a `role="alert"` notice
    // and does NOT redirect — the `?reason=session_expired` path itself has
    // no reachable trigger yet since no protected/guarded route exists (see
    // `shared-states.spec.ts`'s coverage note) — that's for a route guard in
    // a future module, not this one.
    let refreshCallCount = 0;
    await page.route("**/v1/auth/refresh", async (route) => {
      refreshCallCount += 1;
      await route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify(apiError("INVALID_REFRESH_TOKEN", "Refresh token is invalid")),
      });
    });

    let logoutCallCount = 0;
    await page.route("**/v1/auth/logout", async (route) => {
      logoutCallCount += 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify(apiSuccess(null)),
      });
    });

    await page.goto("/student/dashboard");
    const signOut = page.getByRole("button", { name: "Sign out" });
    await signOut.click();

    await expect(page.getByRole("alert").filter({ hasText: "Refresh token is invalid" })).toBeVisible();
    await expect(page).toHaveURL(/\/student\/dashboard$/);

    expect(refreshCallCount).toBe(1);
    // The protected logout call is never attempted once the token refresh
    // that would have supplied its Authorization header has failed.
    expect(logoutCallCount).toBe(0);
  });
});
