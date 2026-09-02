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
 * As of MVP-006 Student Management, `/student/dashboard` and
 * `/tenant-admin/dashboard` are behind `RouteGuard` (see
 * `route-groups.spec.ts`); as of MVP-014 Teacher Dashboard, `/teacher/dashboard`
 * is too (only `/platform-admin/dashboard` remains unguarded, and it uses a
 * distinct `kind="platform-admin"` logout flow this file doesn't otherwise
 * exercise, so it isn't a drop-in substitute here). The scenario below that
 * needs to reach a dashboard with "no cached token" and no prior login
 * (scenario B) uses `/teacher/dashboard`; because `RouteGuard` itself now
 * performs the initial silent refresh on mount (via the same
 * `ensureAccessToken`, before the Sign Out button even renders),
 * `authorizedFetch`'s own call finds an already-cached session and makes no
 * second refresh call — the assertions below still hold (exactly one refresh
 * call total, carrying the token that ends up on the logout request), just
 * triggered by `RouteGuard` rather than by `authorizedFetch` directly. The
 * failed-refresh scenario further below is restructured for the same reason
 * — see its own comment.
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
  test("a fresh session with no in-memory token refreshes once (via RouteGuard) before the protected call reuses it", async ({
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
    // `/teacher/dashboard` (MVP-014) fetches its own course list — mock it so
    // the page settles deterministically; irrelevant to this test's actual
    // refresh/logout assertions.
    await mockJson(page, "**/v1/courses*", 200, apiSuccess({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }));

    // No prior login in this test — the access token is in-memory only and a
    // fresh page load never had one to begin with. `RouteGuard` (now wrapping
    // `(teacher)/layout.tsx` per MVP-014) is what actually triggers this
    // scenario's single refresh call, on mount, before the Sign Out button
    // even renders.
    await page.goto("/teacher/dashboard");
    await page.getByRole("button", { name: "Sign out" }).click();

    await expect(page).toHaveURL(/\/login$/);

    expect(refreshCallCount).toBe(1);
    expect(logoutCallCount).toBe(1);
    expect(logoutAuthHeader).toBe(`Bearer ${refreshedToken}`);
  });
});

test.describe("silent refresh — refresh itself fails", () => {
  test("a failed refresh (on the recoverable-401 retry) surfaces an alert instead of silently redirecting", async ({
    page,
  }) => {
    // `ensureAccessToken`'s doc comment (lib/auth/auth-context.tsx) says
    // callers are responsible for redirecting to login with
    // `?reason=session_expired` when refresh fails. The only real caller in
    // this module is `logout()`, which propagates the failure rather than
    // swallowing it; `LogoutControl` surfaces it as a `role="alert"` notice
    // and does NOT redirect — the `?reason=session_expired` path itself is
    // exercised by `RouteGuard` directly (see `route-guard.tsx`), not by this
    // test, which stays scoped to `authorizedFetch`'s own retry/propagation
    // behavior via `logout()`.
    //
    // As of MVP-014, `(teacher)/layout.tsx` is itself wrapped in
    // `RouteGuard`, so a bare "no cached token, refresh fails" scenario would
    // now be intercepted by the guard's own redirect-on-failure before the
    // Sign Out button ever renders (there is no longer any unguarded
    // `kind="tenant"` dashboard left to reach it through — see this file's
    // module doc). This test is restructured to reach the same
    // `authorizedFetch`-level "refresh itself fails" behavior via its other
    // trigger: the *first* refresh call (`RouteGuard`'s own, on mount) must
    // succeed so the dashboard actually renders; the protected logout call
    // then returns a recoverable 401 (`SESSION_REVOKED`), which drives
    // `authorizedFetch`'s forced-refresh retry — and it is that *second*
    // refresh call that fails here.
    let refreshCallCount = 0;
    await page.route("**/v1/auth/refresh", async (route) => {
      refreshCallCount += 1;
      if (refreshCallCount === 1) {
        await route.fulfill({
          status: 200,
          contentType: "application/json",
          body: JSON.stringify(apiSuccess(refreshResponseBody(fakeJwt({ role: "TEACHER" })))),
        });
        return;
      }
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
        status: 401,
        contentType: "application/json",
        body: JSON.stringify(apiError("SESSION_REVOKED", "Session has been revoked")),
      });
    });
    // `/teacher/dashboard` (MVP-014) fetches its own course list — mock it so
    // the page settles deterministically; irrelevant to this test's actual
    // refresh/logout assertions.
    await mockJson(page, "**/v1/courses*", 200, apiSuccess({ content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }));

    await page.goto("/teacher/dashboard");
    const signOut = page.getByRole("button", { name: "Sign out" });
    await signOut.click();

    await expect(page.getByRole("alert").filter({ hasText: "Refresh token is invalid" })).toBeVisible();
    await expect(page).toHaveURL(/\/teacher\/dashboard$/);

    expect(refreshCallCount).toBe(2);
    // The protected logout call is attempted exactly once (returning the
    // recoverable 401 that triggers the forced-refresh retry above) — it is
    // never retried once that retry's own token refresh has failed.
    expect(logoutCallCount).toBe(1);
  });
});
