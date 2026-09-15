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
 * MVP-020 "Platform Admin Dashboard" — Cross-Tenant Payment Dashboard
 * (`/platform-admin/payments`, `/platform-admin/payments/[tenantId]`,
 * PADASH-2). Read-only, ledger-derived: no refund/adjustment action is
 * reachable from either screen.
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`'s
 * module doc) — every scenario mocks `GET /v1/platform-admin/payments/**`
 * shaped like the documented `ApiResponse<PageResponse<T>>` envelope.
 */

function nowIso(): string {
  return new Date().toISOString();
}

function ledgerEntry(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: `entry-${Math.random().toString(36).slice(2)}`,
    tenantId: "tenant-1",
    tenantName: "Example Institute A",
    orderId: "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    paymentId: "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
    entryType: "PAYMENT_CONFIRMED",
    amount: 100,
    reversesEntryId: null,
    createdAt: nowIso(),
    ...overrides,
  };
}

function tenantDetail(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: "tenant-1",
    name: "Example Institute A",
    subdomain: "example-institute-a",
    status: "active",
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

/** Asserts no mutation control of any kind exists in the given scope. */
async function expectNoMutationControls(page: Page) {
  await expect(page.getByRole("checkbox")).toHaveCount(0);
  await expect(page.getByRole("menu")).toHaveCount(0);
  await expect(page.getByRole("menuitem")).toHaveCount(0);
  for (const name of [/^Approve/i, /^Reject/i, /^Refund/i, /^Edit/i, /^Delete/i, /^Remove/i]) {
    await expect(page.getByRole("button", { name })).toHaveCount(0);
    await expect(page.getByRole("link", { name })).toHaveCount(0);
  }
}

test.describe("Platform Admin Payments Dashboard", () => {
  test("every row visibly names its tenant", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/payments/dashboard*",
      200,
      apiPageSuccess([
        ledgerEntry({ id: "e-1", tenantId: "t-1", tenantName: "Example Institute A" }),
        ledgerEntry({ id: "e-2", tenantId: "t-2", tenantName: "Example Academy", entryType: "REFUND" }),
      ])
    );

    await page.goto("/platform-admin/payments");

    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    const rows = table.locator("tbody tr");
    await expect(rows).toHaveCount(2);
    await expect(
      rows.filter({ hasText: "Example Institute A" }).getByRole("link", { name: "Example Institute A" })
    ).toBeVisible();
    await expect(
      rows.filter({ hasText: "Example Academy" }).getByRole("link", { name: "Example Academy" })
    ).toBeVisible();
  });

  test("is read-only: no mutation control exists anywhere on the dashboard", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(page, "**/v1/platform-admin/payments/dashboard*", 200, apiPageSuccess([ledgerEntry()]));

    await page.goto("/platform-admin/payments");
    await expect(page.getByRole("table")).toBeVisible();

    await expectNoMutationControls(page);
  });

  // Post-review recommended improvement (qa-test-engineer): none of the
  // three new PADASH-2 screens had a Playwright loading-state assertion,
  // against `frontend/CLAUDE.md`'s baseline requirement. Mirrors
  // `audit-log.spec.ts`'s identical gated-route technique.
  test("true initial loading state shows an aria-busy 'Loading…' status, then resolves", async ({ page }) => {
    await mockPlatformAdminSession(page);

    let releaseList: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseList = resolve;
    });
    await page.route("**/v1/platform-admin/payments/dashboard*", async (route) => {
      await gate;
      await fulfillJson(route, 200, apiPageSuccess([ledgerEntry()]));
    });

    await page.goto("/platform-admin/payments");

    const status = page.getByRole("status").filter({ hasText: "Loading…" });
    await expect(status).toBeVisible();
    await expect(status).toHaveAttribute("aria-busy", "true");
    await expect(page.getByRole("table")).toHaveCount(0);

    releaseList?.();
    await expect(page.getByRole("table")).toBeVisible();
    await expect(status).toHaveCount(0);
  });

  test("has exactly one empty state — this endpoint takes no filter params", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(page, "**/v1/platform-admin/payments/dashboard*", 200, apiPageSuccess([]));

    await page.goto("/platform-admin/payments");

    const emptyState = page.getByRole("status").filter({ hasText: "No payments recorded platform-wide yet" });
    await expect(emptyState).toBeVisible();
    // No filters exist on this screen, so a second "no results match your
    // filter" variant would misrepresent a condition that can never occur.
    await expect(page.getByLabel("Status")).toHaveCount(0);
    await expect(page.getByRole("button", { name: /reset filters|clear filters/i })).toHaveCount(0);
  });

  test("below md, the dashboard renders as a card list, not the desktop table", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/payments/dashboard*",
      200,
      apiPageSuccess([ledgerEntry({ tenantName: "Example Institute A" })])
    );

    await page.goto("/platform-admin/payments");

    await expect(page.getByRole("table")).toBeHidden();
    const cardList = page.getByRole("list", { name: "Platform payments" });
    await expect(cardList).toBeVisible();
    await expect(cardList.getByText("Example Institute A")).toBeVisible();
  });

  // MVP-020 review finding: only the tenant-list screen had a
  // permission-denied test; this dashboard shares the same
  // `QueryStateBoundary` wiring but was unverified.
  test("a real 403 renders the permission-denied state, not a crash", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/payments/dashboard*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view payments.")
    );

    await page.goto("/platform-admin/payments");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/platform-admin/dashboard"
    );
  });
});

test.describe("Platform Admin Tenant Payments Drill-down", () => {
  test("the persistent tenant-context banner has no dismiss affordance and survives a page-turn", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/tenant-1",
      200,
      apiSuccess(tenantDetail({ name: "Example Institute A" }))
    );
    await mockJson(
      page,
      "**/v1/platform-admin/payments/tenants/tenant-1*",
      200,
      apiPageSuccess([ledgerEntry({ id: "e-1" })], { page: 0, totalElements: 21, totalPages: 2 })
    );

    await page.goto("/platform-admin/payments/tenant-1");

    const banner = page.getByRole("note", { name: "Viewing tenant: Example Institute A" });
    await expect(banner).toBeVisible();
    // Genuinely no dismiss/close affordance anywhere in the banner region —
    // asserted as a total absence of any button, not just the obvious names.
    await expect(banner.getByRole("button")).toHaveCount(0);
    await expect(page.getByRole("table")).toBeVisible();

    await mockJson(
      page,
      "**/v1/platform-admin/payments/tenants/tenant-1*",
      200,
      apiPageSuccess([ledgerEntry({ id: "e-2" })], { page: 1, totalElements: 21, totalPages: 2 })
    );
    await page.getByRole("button", { name: "Next", exact: true }).click();

    await expect(page.getByText("Page 2 of 2")).toBeVisible();
    // Same tenant name, still visible, still no dismiss control, after the
    // page-turn refetch.
    await expect(banner).toBeVisible();
    await expect(banner).toContainText("Example Institute A");
    await expect(banner.getByRole("button")).toHaveCount(0);
  });

  test("is read-only: no mutation control exists anywhere on the drill-down", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/tenant-1",
      200,
      apiSuccess(tenantDetail({ name: "Example Institute A" }))
    );
    await mockJson(
      page,
      "**/v1/platform-admin/payments/tenants/tenant-1*",
      200,
      apiPageSuccess([ledgerEntry()])
    );

    await page.goto("/platform-admin/payments/tenant-1");
    await expect(page.getByRole("table")).toBeVisible();

    await expectNoMutationControls(page);
  });

  test("an unknown tenant id shows an error state instead of crashing or rendering an empty table as if the tenant existed", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/does-not-exist",
      404,
      apiError("NOT_FOUND", "Tenant not found.")
    );
    // The ledger drill-down query fires unconditionally alongside the tenant
    // lookup (two independent hooks) — mocked too so an unmocked-route
    // failure can't be mistaken for this test's own assertion.
    await mockJson(
      page,
      "**/v1/platform-admin/payments/tenants/does-not-exist*",
      404,
      apiError("NOT_FOUND", "Tenant not found.")
    );

    await page.goto("/platform-admin/payments/does-not-exist");

    const errorAlert = page.getByRole("alert").filter({ hasText: "Tenant not found." });
    await expect(errorAlert).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
  });

  // MVP-020 review finding: no permission-denied test existed for the
  // drill-down at all. A 403 on the tenant-detail query is the primary
  // error to surface here too (mirrors the 404 "unknown tenant id" test
  // above) — both queries fire unconditionally, so both are mocked to the
  // same error so an unmocked-route failure can't be mistaken for this
  // test's own assertion.
  test("a real 403 on the tenant lookup renders the permission-denied state, not a crash", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/tenant-1",
      403,
      apiError("FORBIDDEN", "You do not have permission to view this tenant.")
    );
    await mockJson(
      page,
      "**/v1/platform-admin/payments/tenants/tenant-1*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view this tenant.")
    );

    await page.goto("/platform-admin/payments/tenant-1");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
  });
});
