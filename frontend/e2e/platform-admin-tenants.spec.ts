import { test, expect, type Page } from "@playwright/test";
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
 * MVP-020 "Platform Admin Dashboard" — Tenant List / Approval Queue
 * (`/platform-admin/tenants`, `/platform-admin/tenants/[tenantId]`, PADASH-1).
 *
 * Supersedes the deleted local-mock-data scaffold version of this file (no
 * more "Showing sample data" banner, no client-side search — the live
 * endpoint has no search param — and Approve/Reject now exist and actually
 * call the backend). No real backend runs in this environment (see
 * `fixtures/auth-mocks.ts`'s module doc) — every scenario mocks
 * `GET/POST /v1/platform-admin/tenants/**` shaped like the documented
 * `ApiResponse<T>`/`PageResponse<T>` envelope.
 */

function nowIso(): string {
  return new Date().toISOString();
}

function tenantSummary(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    name: "Example Institute A",
    subdomain: "example-institute-a",
    status: "PENDING_APPROVAL",
    requestedPlan: "STARTER",
    createdAt: nowIso(),
    ...overrides,
  };
}

function tenantDetail(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "11111111-1111-1111-1111-111111111111",
    name: "Example Institute A",
    subdomain: "example-institute-a",
    status: "PENDING_APPROVAL",
    requestedPlan: "STARTER",
    contactName: "Jane Doe",
    contactEmail: "jane@example.test",
    contactPhone: "+1 555 0100",
    createdAt: nowIso(),
    ...overrides,
  };
}

/** Establishes a Platform Admin session by mocking `POST /v1/platform-admin/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
async function mockPlatformAdminSession(page: Page): Promise<void> {
  const token = fakeJwt({ role: "PLATFORM_ADMIN" });
  await mockJson(page, "**/v1/platform-admin/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

test.describe("Platform Admin Tenant List — table content", () => {
  test("every row visibly names its tenant", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([
        tenantSummary({ id: "id-1", name: "Example Institute A" }),
        tenantSummary({ id: "id-2", name: "Example Academy", status: "ACTIVE" }),
      ])
    );

    await page.goto("/platform-admin/tenants");

    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    const rows = table.locator("tbody tr");
    await expect(rows).toHaveCount(2);
    // Scoped to a single row's link, not just presence anywhere on the page —
    // proves the name is a genuine per-row identifier.
    await expect(
      rows.filter({ hasText: "Example Institute A" }).getByRole("link", { name: "Example Institute A" })
    ).toBeVisible();
    await expect(
      rows.filter({ hasText: "Example Academy" }).getByRole("link", { name: "Example Academy" })
    ).toBeVisible();
  });
});

test.describe("Platform Admin Tenant List — destructive actions always name the tenant", () => {
  test("Approve/Reject render only next to the pending tenant's visible name, and the confirmation dialog names the tenant", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A" })])
    );

    await page.goto("/platform-admin/tenants");

    const table = page.getByRole("table");
    const row = table.locator("tbody tr").filter({ hasText: "Example Institute A" });
    await expect(row.getByRole("link", { name: "Example Institute A" })).toBeVisible();
    await expect(row.getByRole("button", { name: "Approve" })).toBeVisible();
    await expect(row.getByRole("button", { name: "Reject" })).toBeVisible();

    await row.getByRole("button", { name: "Approve" }).click();
    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByRole("heading", { name: "Approve Example Institute A?" })).toBeVisible();
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).not.toBeVisible();

    await row.getByRole("button", { name: "Reject" }).click();
    await expect(dialog.getByRole("heading", { name: "Reject Example Institute A?" })).toBeVisible();
  });

  test("on the detail screen, Approve/Reject render only when the tenant's name already anchors the page as its title", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/id-1",
      200,
      apiSuccess(tenantDetail({ id: "id-1", name: "Example Institute A" }))
    );

    await page.goto("/platform-admin/tenants/id-1");

    await expect(page.getByRole("heading", { name: "Example Institute A", level: 1 })).toBeVisible();
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByRole("heading", { name: "Approve Example Institute A?" })).toBeVisible();
  });
});

test.describe("Platform Admin Tenant List — approve/reject flow", () => {
  test("approving a pending tenant list row calls the approve endpoint, closes the dialog, and the row has no actions after refetch", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A", status: "PENDING_APPROVAL" })])
    );

    let approveCalled = false;
    await page.route("**/v1/platform-admin/tenants/id-1/approve", async (route) => {
      approveCalled = true;
      await fulfillJson(
        route,
        200,
        apiSuccess(tenantDetail({ id: "id-1", name: "Example Institute A", status: "ACTIVE" }))
      );
    });

    await page.goto("/platform-admin/tenants");
    const row = page.getByRole("table").locator("tbody tr").filter({ hasText: "Example Institute A" });
    await row.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    // The list refetches on success — return the now-active tenant.
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A", status: "ACTIVE" })])
    );

    await dialog.getByRole("button", { name: "Approve" }).click();

    await expect(dialog).not.toBeVisible();
    expect(approveCalled).toBe(true);
    await expect(row.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(row.getByRole("button", { name: "Reject" })).toHaveCount(0);
    await expect(row.getByText("No action available")).toBeVisible();
  });

  test("rejecting a pending tenant from the detail screen calls the reject endpoint, closes the dialog, and hides the actions after refetch", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/id-1",
      200,
      apiSuccess(tenantDetail({ id: "id-1", name: "Example Institute A", status: "PENDING_APPROVAL" }))
    );

    let rejectCalled = false;
    await page.route("**/v1/platform-admin/tenants/id-1/reject", async (route) => {
      rejectCalled = true;
      await fulfillJson(
        route,
        200,
        apiSuccess(tenantDetail({ id: "id-1", name: "Example Institute A", status: "REJECTED" }))
      );
    });

    await page.goto("/platform-admin/tenants/id-1");
    await expect(page.getByRole("heading", { name: "Example Institute A", level: 1 })).toBeVisible();
    await page.getByRole("button", { name: "Reject" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog.getByRole("heading", { name: "Reject Example Institute A?" })).toBeVisible();

    // The detail query is invalidated on success — return the now-rejected tenant.
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/id-1",
      200,
      apiSuccess(tenantDetail({ id: "id-1", name: "Example Institute A", status: "REJECTED" }))
    );

    await dialog.getByRole("button", { name: "Reject" }).click();

    await expect(dialog).not.toBeVisible();
    expect(rejectCalled).toBe(true);
    await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Reject" })).toHaveCount(0);
  });

  test("a 409 CONFLICT ('already processed') on approve is shown inline and the dialog stays open", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A", status: "PENDING_APPROVAL" })])
    );
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/id-1/approve",
      409,
      apiError("CONFLICT", "Tenant is not pending approval")
    );

    await page.goto("/platform-admin/tenants");
    const row = page.getByRole("table").locator("tbody tr").filter({ hasText: "Example Institute A" });
    await row.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await dialog.getByRole("button", { name: "Approve" }).click();

    // Stays open with an inline message — not a generic crash/blank state.
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("alert")).toContainText("already processed by another admin");
  });
});

test.describe("Platform Admin Tenant List — confirmation dialog keyboard operability", () => {
  // Per module plan §18 ("Confirmation-dialog keyboard operability and focus
  // trap ... verify, don't assume, once wired to a real async mutation") and
  // `.claude/rules/ui-ux.md` §4 ("Modals must trap focus while open and
  // return focus to the triggering element on close"). Mirrors
  // `manual-payment-slips.spec.ts`'s identical focus-trap technique for
  // `approve-slip-dialog.tsx` — same shared `AlertDialog` primitive.
  test("the approve dialog traps focus while open and returns focus to the trigger button on Cancel", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A" })])
    );

    await page.goto("/platform-admin/tenants");
    const trigger = page.getByRole("button", { name: "Approve" });
    await trigger.focus();
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    // Focus starts inside the modal — verify it never lands on the now-inert
    // page content behind it. Tab repeatedly through every focusable control
    // in the dialog and confirm focus never leaves it.
    const focusableCount = await dialog
      .locator("button, input, a[href], [tabindex]:not([tabindex='-1'])")
      .count();
    for (let i = 0; i < focusableCount + 2; i += 1) {
      await page.keyboard.press("Tab");
      await expect(dialog.locator(":focus")).toHaveCount(1);
    }

    // Closing via Cancel returns focus to the element that opened the
    // dialog, so a keyboard user isn't dropped back at the top of the page.
    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });

  test("the reject dialog traps focus while open and returns focus to the trigger button on Cancel", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A" })])
    );

    await page.goto("/platform-admin/tenants");
    const trigger = page.getByRole("button", { name: "Reject" });
    await trigger.focus();
    await trigger.click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    const focusableCount = await dialog
      .locator("button, input, a[href], [tabindex]:not([tabindex='-1'])")
      .count();
    for (let i = 0; i < focusableCount + 2; i += 1) {
      await page.keyboard.press("Tab");
      await expect(dialog.locator(":focus")).toHaveCount(1);
    }

    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toHaveCount(0);
    await expect(trigger).toBeFocused();
  });

  test("Escape does not dismiss the approve dialog while the approval is submitting", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A" })])
    );

    // Held open deliberately — `approve-tenant-dialog.tsx`'s own
    // `onOpenChange` blocks an escape-key close only while
    // `mutation.isPending`; this proves that guard, not just the dialog's
    // default open/close wiring.
    let releaseApprove: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseApprove = resolve;
    });
    await page.route("**/v1/platform-admin/tenants/id-1/approve", async (route) => {
      await gate;
      await fulfillJson(
        route,
        200,
        apiSuccess(tenantDetail({ id: "id-1", name: "Example Institute A", status: "ACTIVE" }))
      );
    });

    await page.goto("/platform-admin/tenants");
    await page.getByRole("button", { name: "Approve" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();
    await dialog.getByRole("button", { name: "Approve" }).click();
    await expect(dialog.getByRole("button", { name: "Submitting…" })).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(dialog).toBeVisible();

    releaseApprove?.();
    await expect(dialog).toHaveCount(0);
  });

  // Post-review required correction: `reject-tenant-dialog.tsx` previously
  // only blocked `escape-key` while `mutation.isPending`, matching the
  // approve dialog above - but Reject is a destructive, one-directional
  // confirmation, and docs/ui-ux/component-library-spec.md §4.3 requires
  // Escape AND scrim-click (outside-press) to be disabled UNCONDITIONALLY
  // for destructive confirmations, not just mid-submission. This proves the
  // fix, both while idle and while submitting.
  test("Escape and scrim-click never dismiss the reject dialog, even while idle", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A" })])
    );

    await page.goto("/platform-admin/tenants");
    await page.getByRole("button", { name: "Reject" }).click();

    const dialog = page.getByRole("alertdialog");
    await expect(dialog).toBeVisible();

    await page.keyboard.press("Escape");
    await expect(dialog).toBeVisible();

    await page.mouse.click(4, 4);
    await expect(dialog).toBeVisible();

    await dialog.getByRole("button", { name: "Cancel" }).click();
    await expect(dialog).toHaveCount(0);
  });
});

test.describe("Platform Admin Tenant List — loading state", () => {
  // Post-review recommended improvement (qa-test-engineer): none of the
  // three new PADASH screens had a Playwright loading-state assertion,
  // against `frontend/CLAUDE.md`'s baseline requirement. Mirrors
  // `audit-log.spec.ts`'s identical gated-route technique.
  test("true initial loading state shows an aria-busy 'Loading…' status, then resolves", async ({ page }) => {
    await mockPlatformAdminSession(page);

    let releaseList: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseList = resolve;
    });
    await page.route("**/v1/platform-admin/tenants*", async (route) => {
      await gate;
      await fulfillJson(route, 200, apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A" })]));
    });

    await page.goto("/platform-admin/tenants");

    const status = page.getByRole("status").filter({ hasText: "Loading…" });
    await expect(status).toBeVisible();
    await expect(status).toHaveAttribute("aria-busy", "true");
    await expect(page.getByRole("table")).toHaveCount(0);

    releaseList?.();
    await expect(page.getByRole("table")).toBeVisible();
    await expect(status).toHaveCount(0);
  });
});

test.describe("Platform Admin Tenant List — empty states", () => {
  test("true zero-data empty state (no tenants registered yet) uses distinct copy from a status-filtered zero-match state", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(page, "**/v1/platform-admin/tenants*", 200, apiPageSuccess([]));

    await page.goto("/platform-admin/tenants");

    const trueEmpty = page.getByRole("status").filter({ hasText: "No tenant registrations yet" });
    await expect(trueEmpty).toBeVisible();
  });

  test("a status filter with zero matches shows a distinct empty state, and clearing it restores the (cached) full list", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A" })])
    );

    await page.goto("/platform-admin/tenants");
    await expect(page.getByRole("table")).toBeVisible();

    // Selecting a status filter for the first time is a distinct query key
    // from the initial (unfiltered) load, so this mock is guaranteed to be
    // hit fresh rather than served from the 30s `staleTime` cache.
    await mockJson(page, "**/v1/platform-admin/tenants*", 200, apiPageSuccess([]));
    await page.getByLabel("Status").selectOption("CANCELLED");

    const filteredEmpty = page.getByRole("status").filter({ hasText: "No tenants with this status" });
    await expect(filteredEmpty).toBeVisible();
    await expect(page.getByText("No tenant registrations yet")).toHaveCount(0);

    // "View all tenants" returns to the exact (unfiltered) query key already
    // cached moments ago with real content — restored from cache, matching
    // real user-perceived behavior, with no further network mock needed.
    await filteredEmpty.getByRole("button", { name: "View all tenants" }).click();
    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    await expect(table.getByText("Example Institute A")).toBeVisible();
  });
});

test.describe("Platform Admin Tenant List — responsive behavior", () => {
  test("the table/card-list swap happens exactly at Tailwind's md breakpoint (768px)", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      200,
      apiPageSuccess([tenantSummary({ id: "id-1", name: "Example Institute A" })])
    );

    const cardList = page.getByRole("list").filter({ hasText: "Example Institute A" });

    await page.setViewportSize({ width: 767, height: 900 });
    await page.goto("/platform-admin/tenants");
    await expect(page.getByRole("table")).toBeHidden();
    await expect(cardList).toBeVisible();

    await page.setViewportSize({ width: 768, height: 900 });
    await expect(page.getByRole("table")).toBeVisible();
    await expect(cardList).toBeHidden();
  });
});

test.describe("Platform Admin Tenant List — permission-denied UX", () => {
  test("a real 403 renders the permission-denied state, not a crash", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view tenants.")
    );

    await page.goto("/platform-admin/tenants");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/platform-admin/dashboard"
    );
  });

  // MVP-020 review finding: only the list screen had a permission-denied
  // test; the detail screen shares the same `QueryStateBoundary` wiring but
  // was unverified.
  test("a real 403 on the detail screen renders the permission-denied state, not a crash", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/id-1",
      403,
      apiError("FORBIDDEN", "You do not have permission to view this tenant.")
    );

    await page.goto("/platform-admin/tenants/id-1");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/platform-admin/dashboard"
    );
  });
});
