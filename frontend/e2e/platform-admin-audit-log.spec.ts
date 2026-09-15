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
 * MVP-020 "Platform Admin Dashboard" — Platform Audit Log
 * (`/platform-admin/audit-log`, `/platform-admin/audit-log/[tenantId]`,
 * PADASH-2). Structurally distinct from the Tenant Admin/Read-only Auditor
 * Audit Log Viewer covered by `audit-log.spec.ts` — different endpoint,
 * every row here carries its own tenant, and there is no `targetEntity`
 * filter field on this screen.
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`'s
 * module doc) — every scenario mocks `GET /v1/platform-admin/audit-log/**`
 * shaped like the documented `ApiResponse<PageResponse<T>>` envelope.
 */

function nowIso(): string {
  return new Date().toISOString();
}

function auditEntry(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: `entry-${Math.random().toString(36).slice(2)}`,
    tenantId: "tenant-1",
    tenantName: "Example Institute A",
    actorId: "cccccccc-cccc-cccc-cccc-cccccccccccc",
    action: "tenant.approved",
    targetEntity: "tenant",
    targetId: "dddddddd-dddd-dddd-dddd-dddddddddddd",
    reason: null,
    metadata: null,
    occurredAt: nowIso(),
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

test.describe("Platform Admin Audit Log — table content", () => {
  test("every row visibly names its tenant", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/audit-log*",
      200,
      apiPageSuccess([
        auditEntry({ id: "e-1", tenantId: "t-1", tenantName: "Example Institute A" }),
        auditEntry({ id: "e-2", tenantId: "t-2", tenantName: "Example Academy", action: "tenant.rejected" }),
      ])
    );

    await page.goto("/platform-admin/audit-log");

    const table = page.getByRole("table");
    await expect(table).toBeVisible();
    // Scoped by `row` role (not raw `tbody tr` indices) because this table
    // also carries an interleaved, initially-hidden expand-detail `<tr>` per
    // row — scoping by content, not position, avoids any ambiguity from that.
    const rowA = table.getByRole("row").filter({ hasText: "Example Institute A" });
    const rowB = table.getByRole("row").filter({ hasText: "Example Academy" });
    await expect(rowA).toHaveCount(1);
    await expect(rowB).toHaveCount(1);
    await expect(rowA.getByRole("link", { name: "Example Institute A" })).toBeVisible();
    await expect(rowB.getByRole("link", { name: "Example Academy" })).toBeVisible();
  });
});

test.describe("Platform Admin Audit Log — loading state", () => {
  // Post-review recommended improvement (qa-test-engineer): none of the
  // three new PADASH-2 screens had a Playwright loading-state assertion,
  // against `frontend/CLAUDE.md`'s baseline requirement. Mirrors
  // `audit-log.spec.ts`'s identical gated-route technique for the
  // tenant-scoped Audit Log Viewer.
  test("true initial loading state shows an aria-busy 'Loading…' status, then resolves", async ({ page }) => {
    await mockPlatformAdminSession(page);

    let releaseList: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseList = resolve;
    });
    await page.route("**/v1/platform-admin/audit-log*", async (route) => {
      await gate;
      await fulfillJson(route, 200, apiPageSuccess([auditEntry()]));
    });

    await page.goto("/platform-admin/audit-log");

    const status = page.getByRole("status").filter({ hasText: "Loading audit log…" });
    await expect(status).toBeVisible();
    await expect(status).toHaveAttribute("aria-busy", "true");
    await expect(page.getByRole("table")).toHaveCount(0);

    releaseList?.();
    await expect(page.getByRole("table")).toBeVisible();
    await expect(status).toHaveCount(0);
  });
});

test.describe("Platform Admin Audit Log — no mutation affordance", () => {
  test("no update/delete affordance exists anywhere in the DOM", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(page, "**/v1/platform-admin/audit-log*", 200, apiPageSuccess([auditEntry()]));

    await page.goto("/platform-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    await expect(page.getByRole("checkbox")).toHaveCount(0);
    await expect(page.getByRole("menu")).toHaveCount(0);
    await expect(page.getByRole("menuitem")).toHaveCount(0);
    for (const name of [/^Edit/i, /^Delete/i, /^Remove/i, /^Approve/i, /^Reject/i]) {
      await expect(page.getByRole("button", { name })).toHaveCount(0);
      await expect(page.getByRole("link", { name })).toHaveCount(0);
    }
  });
});

test.describe("Platform Admin Audit Log — empty states", () => {
  test("the true zero-data empty state and the filtered-to-zero empty state use distinct copy", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(page, "**/v1/platform-admin/audit-log*", 200, apiPageSuccess([]));

    await page.goto("/platform-admin/audit-log");

    const trueEmpty = page.getByRole("status").filter({ hasText: "No platform audit events yet" });
    await expect(trueEmpty).toBeVisible();
    await expect(trueEmpty.getByRole("button", { name: "Reset filters" })).toHaveCount(0);

    await mockJson(page, "**/v1/platform-admin/audit-log*", 200, apiPageSuccess([auditEntry()]));
    await page.goto("/platform-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    await mockJson(page, "**/v1/platform-admin/audit-log*", 200, apiPageSuccess([]));
    await page.getByLabel("Action", { exact: true }).fill("some.unknown_action");
    await page.getByRole("button", { name: "Apply filters" }).click();

    const filteredEmpty = page.getByRole("status").filter({ hasText: "No events match your filters" });
    await expect(filteredEmpty).toBeVisible();
    await expect(page.getByText("No platform audit events yet")).toHaveCount(0);
    await expect(filteredEmpty.getByRole("button", { name: "Reset filters" })).toBeVisible();

    await mockJson(page, "**/v1/platform-admin/audit-log*", 200, apiPageSuccess([auditEntry()]));
    await filteredEmpty.getByRole("button", { name: "Reset filters" }).click();
    await expect(page.getByRole("table")).toBeVisible();
  });
});

test.describe("Platform Admin Audit Log — filters", () => {
  test("there is no targetEntity field, and date-range/action filters are reflected in the outgoing request", async ({
    page,
  }) => {
    await mockPlatformAdminSession(page);
    await mockJson(page, "**/v1/platform-admin/audit-log*", 200, apiPageSuccess([auditEntry()]));

    await page.goto("/platform-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();
    await expect(page.getByLabel("Target entity", { exact: true })).toHaveCount(0);

    const seenQueries: string[] = [];
    await page.route("**/v1/platform-admin/audit-log*", async (route) => {
      seenQueries.push(new URL(route.request().url()).search);
      await fulfillJson(route, 200, apiPageSuccess([auditEntry({ action: "tenant.rejected" })]));
    });

    await page.getByLabel("From", { exact: true }).fill("2026-01-01");
    await page.getByLabel("To", { exact: true }).fill("2026-01-31");
    await page.getByLabel("Action", { exact: true }).fill("tenant.rejected");
    await page.getByRole("button", { name: "Apply filters" }).click();

    await expect(page.getByRole("table").getByText("tenant.rejected")).toBeVisible();
    expect(seenQueries.some((q) => q.includes("from=2026-01-01") && q.includes("to=2026-01-31"))).toBe(true);
    expect(seenQueries.some((q) => q.includes("action=tenant.rejected"))).toBe(true);
    expect(seenQueries.every((q) => !/targetEntity=/i.test(q))).toBe(true);
    // Tenant identity is resolved server-side from the authenticated session,
    // never from client input (`.claude/rules/tenancy.md`).
    expect(seenQueries.every((q) => !/[?&](tenantId|tenant_id)=/i.test(q))).toBe(true);
  });
});

test.describe("Platform Admin Audit Log — responsive behavior", () => {
  test("below md, the log renders as a card list, not the desktop table", async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/audit-log*",
      200,
      apiPageSuccess([auditEntry({ tenantName: "Example Institute A" })])
    );

    await page.goto("/platform-admin/audit-log");

    await expect(page.getByRole("table")).toBeHidden();
    const cardList = page.getByRole("list", { name: "Platform audit log" });
    await expect(cardList).toBeVisible();
    await expect(cardList.getByText("Example Institute A")).toBeVisible();
  });
});

test.describe("Platform Admin Audit Log — permission-denied UX", () => {
  // MVP-020 review finding: only the tenant-list screen (a different
  // module's page) had a permission-denied test; this screen shares the
  // same `QueryStateBoundary` wiring but was unverified.
  test("a real 403 renders the permission-denied state, not a crash", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/audit-log*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view the audit log.")
    );

    await page.goto("/platform-admin/audit-log");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/platform-admin/dashboard"
    );
  });
});

test.describe("Platform Admin Tenant Audit Log Drill-down", () => {
  test("the persistent tenant-context banner has no dismiss affordance and survives a filter apply", async ({
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
      "**/v1/platform-admin/audit-log/tenants/tenant-1*",
      200,
      apiPageSuccess([auditEntry({ tenantId: "tenant-1" })])
    );

    await page.goto("/platform-admin/audit-log/tenant-1");

    const banner = page.getByRole("note", { name: "Viewing tenant: Example Institute A" });
    await expect(banner).toBeVisible();
    await expect(banner.getByRole("button")).toHaveCount(0);
    await expect(page.getByRole("table")).toBeVisible();

    await mockJson(
      page,
      "**/v1/platform-admin/audit-log/tenants/tenant-1*",
      200,
      apiPageSuccess([auditEntry({ tenantId: "tenant-1", action: "tenant.rejected" })])
    );
    await page.getByLabel("Action", { exact: true }).fill("tenant.rejected");
    await page.getByRole("button", { name: "Apply filters" }).click();

    await expect(page.getByRole("table").getByText("tenant.rejected")).toBeVisible();
    await expect(banner).toBeVisible();
    await expect(banner).toContainText("Example Institute A");
    await expect(banner.getByRole("button")).toHaveCount(0);
  });

  test("no update/delete affordance exists anywhere on the drill-down", async ({ page }) => {
    await mockPlatformAdminSession(page);
    await mockJson(
      page,
      "**/v1/platform-admin/tenants/tenant-1",
      200,
      apiSuccess(tenantDetail({ name: "Example Institute A" }))
    );
    await mockJson(
      page,
      "**/v1/platform-admin/audit-log/tenants/tenant-1*",
      200,
      apiPageSuccess([auditEntry({ tenantId: "tenant-1" })])
    );

    await page.goto("/platform-admin/audit-log/tenant-1");
    await expect(page.getByRole("table")).toBeVisible();

    await expect(page.getByRole("checkbox")).toHaveCount(0);
    await expect(page.getByRole("menu")).toHaveCount(0);
    await expect(page.getByRole("menuitem")).toHaveCount(0);
    for (const name of [/^Edit/i, /^Delete/i, /^Remove/i, /^Approve/i, /^Reject/i]) {
      await expect(page.getByRole("button", { name })).toHaveCount(0);
    }
  });

  // MVP-020 review finding: no permission-denied test existed for the
  // drill-down at all. A 403 on the tenant-detail query is the primary
  // error to surface here (mirrors the payments drill-down's equivalent
  // test) — both queries fire unconditionally, so both are mocked to the
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
      "**/v1/platform-admin/audit-log/tenants/tenant-1*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view this tenant.")
    );

    await page.goto("/platform-admin/audit-log/tenant-1");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
  });
});
