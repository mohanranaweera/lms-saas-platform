import { test, expect, type Page, type Route } from "@playwright/test";
import {
  apiError,
  apiPageSuccess,
  apiSuccess,
  fakeJwt,
  fulfillJson,
  mockJson,
  refreshResponseBody,
} from "./fixtures/auth-mocks";

/**
 * MVP-018 "Email Notifications" — Notification Center frontend screens:
 *   - Student Notification Center (`/student/notifications`)
 *   - Teacher Notification Center (`/teacher/notifications`)
 *
 * Both routes render the same shared `NotificationList` component against
 * `GET /api/v1/notifications` / `PATCH /api/v1/notifications/{id}/read`
 * (`NotificationController` — no `DomainArea`/role gate, self-scoped
 * server-side). No real backend runs in this environment (see
 * `fixtures/auth-mocks.ts`'s module doc) — every scenario mocks `/v1/**`
 * responses shaped like the documented `ApiResponse<T>` envelope.
 */

const NOTIFICATION_1 = "11111111-1111-1111-1111-111111111111";
const NOTIFICATION_2 = "22222222-2222-2222-2222-222222222222";

function nowIso(): string {
  return new Date().toISOString();
}

function notificationBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: NOTIFICATION_1,
    title: "Payment confirmed",
    body: "Your payment for Intro to Biology has been confirmed.",
    readAt: null,
    createdAt: nowIso(),
    ...overrides,
  };
}

/** Establishes a session for the given role by mocking `POST /v1/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
  // Every `(student)`/`(teacher)` route renders the shared nav, which always
  // fetches `GET /v1/notifications/unread-count` for the "Notifications" nav
  // badge (`NotificationsNavBadge`) — default it to zero/no-badge here so
  // tests that don't care about the badge aren't left with an unmocked
  // request. Tests that DO care register their own `page.route` for this URL
  // after calling this helper, which takes priority (Playwright routes match
  // in reverse registration order).
  await mockJson(page, "**/v1/notifications/unread-count", 200, apiSuccess({ unreadCount: 0 }));
}

test.describe("Student Notification Center", () => {
  test("shows skeleton then a populated list distinguishing unread vs read rows, and marking one as read reflects the confirmed server response", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");

    let releaseList: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseList = resolve;
    });
    // Non-overlapping globs (the list read always carries a `?page=&size=`
    // query string from `buildNotificationListQuery`, the mark-read path
    // never does) so there is no route-registration-order ambiguity between
    // the two handlers below.
    let markedRead = false;
    await page.route("**/v1/notifications?*", async (route) => {
      await gate;
      await fulfillJson(
        route,
        200,
        apiPageSuccess([
          notificationBody({
            id: NOTIFICATION_1,
            title: "Payment confirmed",
            readAt: markedRead ? nowIso() : null,
          }),
          notificationBody({
            id: NOTIFICATION_2,
            title: "Payment refunded",
            body: "Your payment has been refunded.",
            readAt: nowIso(),
          }),
        ])
      );
    });
    await page.route(`**/v1/notifications/${NOTIFICATION_1}/read`, async (route) => {
      markedRead = true;
      await fulfillJson(
        route,
        200,
        apiSuccess(notificationBody({ id: NOTIFICATION_1, title: "Payment confirmed", readAt: nowIso() }))
      );
    });

    await page.goto("/student/notifications");

    const loadingAnnouncement = page.getByText("Loading notifications…");
    await expect(loadingAnnouncement).toBeAttached();

    releaseList?.();

    await expect(page.getByText("Payment confirmed")).toBeVisible();
    await expect(page.getByText("Payment refunded")).toBeVisible();

    // Unread row: visible "Unread" badge + Mark as read control.
    const unreadRow = page.getByRole("listitem").filter({ hasText: "Payment confirmed" });
    await expect(unreadRow.getByText("Unread")).toBeVisible();
    // Per-row accessible name (not the generic "Mark as read" shared across
    // every row) so a screen-reader user listing all buttons on the page can
    // tell rows apart.
    const markReadButton = unreadRow.getByRole("button", { name: 'Mark as read: "Payment confirmed"' });
    await expect(markReadButton).toBeVisible();

    // Read row has no "Mark as read" button and no "Unread" badge.
    const readRow = page.getByRole("listitem").filter({ hasText: "Payment refunded" });
    await expect(readRow.getByText("Unread")).toHaveCount(0);
    await expect(readRow.getByRole("button", { name: /^Mark as read/ })).toHaveCount(0);

    await markReadButton.click();

    // The row updates only once the mutation's confirmed server response
    // lands (no optimistic update) — the badge and button disappear.
    await expect(page.getByRole("button", { name: /^Mark as read/ })).toHaveCount(0);
    await expect(page.getByText("Unread")).toHaveCount(0);

    // Focus moves onto the row itself (it's still `unreadRow`, same `<li>`,
    // now read) since the "Mark as read" button it held focus just unmounted
    // — otherwise focus would silently fall back to `<body>`.
    await expect(unreadRow).toBeFocused();
  });

  test("pressing Enter on \"Mark as read\" (keyboard-operable, not click-only) marks the notification read, and the change persists after a full page reload — not merely an optimistic client-side flip", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");

    // Mutable mock state, mirroring `material-visibility.spec.ts`'s
    // reload-persistence pattern: the GET handler always reflects whatever
    // the mark-read PATCH last did, so a fresh `page.reload()` (a real new
    // navigation, no client cache reused) only shows "read" if the server
    // state actually changed — proving this isn't an optimistic-only flip.
    let markedRead = false;
    await page.route("**/v1/notifications?*", async (route) => {
      await fulfillJson(
        route,
        200,
        apiPageSuccess([notificationBody({ readAt: markedRead ? nowIso() : null })])
      );
    });
    await page.route(`**/v1/notifications/${NOTIFICATION_1}/read`, async (route) => {
      markedRead = true;
      await fulfillJson(route, 200, apiSuccess(notificationBody({ readAt: nowIso() })));
    });

    await page.goto("/student/notifications");

    // Plan §11: "Mark-as-read: keyboard-operable (Tab + Enter/Space)" — drive
    // this via real focus + Enter, not `.click()`, mirroring
    // `material-reorder-keyboard.spec.ts`'s pure-keyboard-activation pattern.
    const markReadButton = page.getByRole("button", { name: 'Mark as read: "Payment confirmed"' });
    await markReadButton.focus();
    await expect(markReadButton).toBeFocused();
    await page.keyboard.press("Enter");

    await expect(page.getByRole("button", { name: /^Mark as read/ })).toHaveCount(0);
    await expect(page.getByText("Unread")).toHaveCount(0);

    await page.reload();

    await expect(page.getByText("Payment confirmed")).toBeVisible();
    await expect(page.getByRole("button", { name: /^Mark as read/ })).toHaveCount(0);
    await expect(page.getByText("Unread")).toHaveCount(0);
  });

  test("true empty state (no notifications yet) uses Student-specific copy", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/notifications*", 200, apiPageSuccess([]));

    await page.goto("/student/notifications");

    const emptyState = page.getByRole("status").filter({ hasText: "No notifications yet" });
    await expect(emptyState).toBeVisible();
    await expect(
      emptyState.getByText(/payment is confirmed, rejected, or refunded/)
    ).toBeVisible();
  });

  test("a failed read shows a retryable error, and Retry recovers", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      "**/v1/notifications*",
      500,
      apiError("INTERNAL_ERROR", "Could not load notifications.")
    );

    await page.goto("/student/notifications");

    const errorAlert = page.getByRole("alert").filter({ hasText: "Could not load notifications." });
    await expect(errorAlert).toBeVisible();

    await mockJson(page, "**/v1/notifications*", 200, apiPageSuccess([]));
    await errorAlert.getByRole("button", { name: "Try again" }).click();

    await expect(page.getByText("No notifications yet")).toBeVisible();
  });

  test("permission-denied state on a real 403", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(
      page,
      "**/v1/notifications*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view these notifications.")
    );

    await page.goto("/student/notifications");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/student/dashboard"
    );
  });

  test("a mark-as-read mutation error is surfaced without crashing the page", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/notifications*", 200, apiPageSuccess([notificationBody()]));
    await mockJson(
      page,
      `**/v1/notifications/${NOTIFICATION_1}/read`,
      404,
      apiError("NOT_FOUND", "Notification not found.")
    );

    await page.goto("/student/notifications");
    await page.getByRole("button", { name: /^Mark as read/ }).click();

    // Visible `Alert` (its `role="alert"` default is what announces this, no
    // separate `LiveRegion` needed) — mirrors `mark-attendance-panel.tsx`'s
    // established precedent for surfacing a mutation error on screen.
    const errorAlert = page.getByRole("alert").filter({
      hasText: "Notification not found.",
    });
    await expect(errorAlert).toBeVisible();
    // Page stays usable — the row and button are still present, not crashed.
    await expect(page.getByRole("button", { name: /^Mark as read/ })).toBeVisible();
  });
});

test.describe("Student Notification Center — pagination", () => {
  /** Reads the `page` query param off a mocked `GET /v1/notifications` request. */
  function requestedPage(route: Route): number {
    const url = new URL(route.request().url());
    return Number(url.searchParams.get("page") ?? "0");
  }

  test("Next/Previous navigate pages and respect disabled boundaries on the first and last page", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");

    await page.route("**/v1/notifications?*", async (route) => {
      const pageIndex = requestedPage(route);
      if (pageIndex === 0) {
        await fulfillJson(
          route,
          200,
          apiPageSuccess([notificationBody({ id: NOTIFICATION_1, title: "Payment confirmed" })], {
            page: 0,
            totalPages: 2,
            totalElements: 2,
          })
        );
      } else {
        await fulfillJson(
          route,
          200,
          apiPageSuccess(
            [notificationBody({ id: NOTIFICATION_2, title: "Payment refunded", readAt: nowIso() })],
            { page: 1, totalPages: 2, totalElements: 2 }
          )
        );
      }
    });

    await page.goto("/student/notifications");
    await expect(page.getByText("Payment confirmed")).toBeVisible();

    const previousButton = page.getByRole("button", { name: "Previous", exact: true });
    // `exact` avoids matching Next.js's own dev-tools overlay button (its
    // accessible name is "Open Next.js Dev Tools", which contains "Next" as
    // a substring and would otherwise match too).
    const nextButton = page.getByRole("button", { name: "Next", exact: true });

    // First page: Previous is disabled, Next is enabled.
    await expect(previousButton).toBeDisabled();
    await expect(nextButton).toBeEnabled();
    await expect(page.getByText("Page 1 of 2")).toBeVisible();

    await nextButton.click();

    await expect(page.getByText("Payment refunded")).toBeVisible();
    await expect(page.getByText("Page 2 of 2")).toBeVisible();
    // Last page: Next is disabled, Previous is enabled.
    await expect(nextButton).toBeDisabled();
    await expect(previousButton).toBeEnabled();

    await previousButton.click();

    await expect(page.getByText("Payment confirmed")).toBeVisible();
    await expect(previousButton).toBeDisabled();
  });

  test("paging to a page whose content came back empty shows the distinct 'no more results' state, not the true zero-data empty state", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");

    await page.route("**/v1/notifications?*", async (route) => {
      const pageIndex = requestedPage(route);
      if (pageIndex === 0) {
        await fulfillJson(
          route,
          200,
          apiPageSuccess([notificationBody({ id: NOTIFICATION_1, title: "Payment confirmed" })], {
            page: 0,
            totalPages: 2,
            totalElements: 1,
          })
        );
      } else {
        // Simulates the underlying data changing between fetches (e.g. the
        // row was deleted/expired) so a page the UI still considers valid
        // now comes back empty — must render the "no more results" copy,
        // never the true-empty (page === 0) empty-state copy.
        await fulfillJson(
          route,
          200,
          apiPageSuccess([], { page: pageIndex, totalPages: 2, totalElements: 1 })
        );
      }
    });

    await page.goto("/student/notifications");
    await expect(page.getByText("Payment confirmed")).toBeVisible();

    await page.getByRole("button", { name: "Next", exact: true }).click();

    await expect(page.getByText("No more results")).toBeVisible();
    await expect(
      page.getByText("There are no notifications on this page. Go back to an earlier page.")
    ).toBeVisible();
    await expect(page.getByText("No notifications yet")).toHaveCount(0);
  });
});

test.describe("Student Notification Center — background poll announcement", () => {
  /**
   * The 30s `refetchInterval` poll (`useNotifications`) must only announce
   * "New notification arrived." when this page's unread count actually
   * *increased* since the previous fetch — a routine poll where nothing
   * changed must stay silent. `page.clock` fast-forwards the interval
   * without a real 30s wait.
   */
  test("announces only when a poll brings a new unread notification, not on a no-op poll", async ({
    page,
  }) => {
    await page.clock.install();
    await mockTenantSession(page, "STUDENT");

    let fetchCount = 0;
    await page.route("**/v1/notifications?*", async (route) => {
      fetchCount += 1;
      // 1st fetch: one unread. 2nd fetch (first poll): unchanged — must stay
      // silent. 3rd fetch (second poll): a new unread notification arrives —
      // must announce.
      const notifications =
        fetchCount >= 3
          ? [
              notificationBody({ id: NOTIFICATION_2, title: "New payment slip submitted", readAt: null }),
              notificationBody({ id: NOTIFICATION_1, title: "Payment confirmed", readAt: null }),
            ]
          : [notificationBody({ id: NOTIFICATION_1, title: "Payment confirmed", readAt: null })];
      await fulfillJson(route, 200, apiPageSuccess(notifications));
    });

    await page.goto("/student/notifications");
    await expect(page.getByText("Payment confirmed")).toBeVisible();
    expect(fetchCount).toBe(1);

    // First poll: unread count unchanged (still 1) — no announcement.
    await page.clock.fastForward(30_000);
    await expect.poll(() => fetchCount).toBe(2);
    await expect(page.getByText("New notification arrived.")).toHaveCount(0);

    // Second poll: unread count increased (1 -> 2) — announces.
    await page.clock.fastForward(30_000);
    await expect.poll(() => fetchCount).toBe(3);
    await expect(page.getByText("New payment slip submitted")).toBeVisible();
    await expect(page.getByText("New notification arrived.")).toBeAttached();
  });
});

test.describe("Teacher Notification Center", () => {
  test("renders the same list at the teacher route with Teacher-specific empty-state copy", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/notifications*", 200, apiPageSuccess([]));

    await page.goto("/teacher/notifications");

    const emptyState = page.getByRole("status").filter({ hasText: "No notifications yet" });
    await expect(emptyState).toBeVisible();
    await expect(emptyState.getByText(/activity feed/)).toBeVisible();
  });

  test("permission-denied state on a real 403 points at the teacher dashboard", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(
      page,
      "**/v1/notifications*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view these notifications.")
    );

    await page.goto("/teacher/notifications");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/teacher/dashboard"
    );
  });
});

test.describe("Notifications nav visibility", () => {
  test("Student nav shows Notifications", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my", 200, apiSuccess([]));

    await page.goto("/student/dashboard");

    await expect(page.getByRole("link", { name: "Notifications" })).toHaveAttribute(
      "href",
      "/student/notifications"
    );
  });

  test("Teacher nav shows Notifications", async ({ page }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));

    await page.goto("/teacher/dashboard");

    await expect(page.getByRole("link", { name: "Notifications" })).toHaveAttribute(
      "href",
      "/teacher/notifications"
    );
  });
});

test.describe("Notifications nav unread-count badge", () => {
  test("Student nav badge renders the count with an accessible name when unread notifications exist", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my", 200, apiSuccess([]));
    // Registered after `mockTenantSession`'s default (0) mock, so this one
    // wins per Playwright's reverse-registration-order route matching.
    await mockJson(page, "**/v1/notifications/unread-count", 200, apiSuccess({ unreadCount: 5 }));

    await page.goto("/student/dashboard");

    // Visible numeral, plus a context-carrying accessible name — not a bare
    // "5" a screen reader would announce with no context.
    const notificationsLink = page.getByRole("link", { name: /5 unread notifications/ });
    await expect(notificationsLink).toBeVisible();
    await expect(notificationsLink).toHaveAttribute("href", "/student/notifications");
    await expect(notificationsLink.getByText("5", { exact: true })).toBeVisible();
  });

  test("Teacher nav badge renders the count with an accessible name when unread notifications exist", async ({
    page,
  }) => {
    await mockTenantSession(page, "TEACHER");
    await mockJson(page, "**/v1/courses*", 200, apiPageSuccess([]));
    await mockJson(page, "**/v1/notifications/unread-count", 200, apiSuccess({ unreadCount: 2 }));

    await page.goto("/teacher/dashboard");

    const notificationsLink = page.getByRole("link", { name: /2 unread notifications/ });
    await expect(notificationsLink).toBeVisible();
    await expect(notificationsLink.getByText("2", { exact: true })).toBeVisible();
  });

  test("badge does not render (not even as \"0\") when the unread count is zero", async ({ page }) => {
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/enrollments/my", 200, apiSuccess([]));
    // `mockTenantSession` already defaults unread-count to 0 — no override needed.

    await page.goto("/student/dashboard");

    const notificationsLink = page.getByRole("link", { name: "Notifications" });
    await expect(notificationsLink).toBeVisible();
    await expect(notificationsLink.getByText("0", { exact: true })).toHaveCount(0);
    await expect(page.locator("[aria-label*='unread notification']")).toHaveCount(0);
  });

  test("marking a notification as read updates the nav badge's count without a full page reload", async ({
    page,
  }) => {
    await mockTenantSession(page, "STUDENT");

    let markedRead = false;
    let unreadCount = 1;
    await page.route("**/v1/notifications?*", async (route) => {
      await fulfillJson(
        route,
        200,
        apiPageSuccess([notificationBody({ readAt: markedRead ? nowIso() : null })])
      );
    });
    await page.route("**/v1/notifications/unread-count", async (route) => {
      await fulfillJson(route, 200, apiSuccess({ unreadCount }));
    });
    await page.route(`**/v1/notifications/${NOTIFICATION_1}/read`, async (route) => {
      markedRead = true;
      unreadCount = 0;
      await fulfillJson(route, 200, apiSuccess(notificationBody({ readAt: nowIso() })));
    });

    await page.goto("/student/notifications");

    const notificationsLink = page.getByRole("link", { name: /1 unread notification/ });
    await expect(notificationsLink).toBeVisible();

    await page.getByRole("button", { name: /^Mark as read/ }).click();
    await expect(page.getByRole("button", { name: /^Mark as read/ })).toHaveCount(0);

    // The badge disappears once the mark-read mutation's `onSuccess`
    // invalidates the unread-count query and it refetches — no
    // `page.reload()`/navigation happens anywhere in this test, proving the
    // update is a live cache invalidation, not something that only shows up
    // after a fresh page load.
    await expect(page.getByRole("link", { name: "Notifications" })).toBeVisible();
    await expect(page.locator("[aria-label*='unread notification']")).toHaveCount(0);
  });
});

/**
 * Narrow-viewport coverage (plan §18 precedent, mirrored from
 * `attendance.spec.ts`): this is a consumer-style, mobile-first surface — at
 * 375px the list rows and their controls must stay visible/reachable and
 * the page body must never scroll horizontally.
 */
test.describe("Student Notification Center — narrow viewport (375x667)", () => {
  test("rows and Mark as read stay visible and clickable, with no horizontal page overflow", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await mockTenantSession(page, "STUDENT");
    await mockJson(page, "**/v1/notifications*", 200, apiPageSuccess([notificationBody()]));

    await page.goto("/student/notifications");

    await expect(page.getByText("Payment confirmed")).toBeVisible();
    const markReadButton = page.getByRole("button", { name: /^Mark as read/ });
    await expect(markReadButton).toBeVisible();

    const hasNoOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1
    );
    expect(hasNoOverflow).toBe(true);
  });
});
