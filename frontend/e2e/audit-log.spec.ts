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
 * MVP-019 "Audit Logs" — Tenant Admin/Read-only Auditor Audit Log Viewer
 * (`/tenant-admin/audit-log`, AUDIT-3, plan §11/§18).
 *
 * No real backend runs in this environment (see `fixtures/auth-mocks.ts`'s
 * module doc) — every scenario mocks `GET /v1/audit-log*` shaped like the
 * documented `ApiResponse<PageResponse<AuditLogEntryResponse>>` envelope
 * (`docs/api/audit-log-management.md`).
 *
 * Not covered here (frontend-only limitation, noted rather than faked):
 * the real cross-tenant/RBAC enforcement itself — that is proven by the
 * backend's `AuditLogCrossTenantIntegrationTest`/authorization tests. This
 * file only proves the frontend correctly renders whatever the (mocked)
 * backend returns, including a real `403`.
 */

function nowIso(): string {
  return new Date().toISOString();
}

function auditLogEntryBody(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    id: `entry-${Math.random().toString(36).slice(2)}`,
    actorId: "11111111-1111-1111-1111-111111111111",
    actorDisplayName: "teacher@example.test",
    action: "course.price_changed",
    targetEntity: "course",
    targetId: "22222222-2222-2222-2222-222222222222",
    reason: null,
    metadata: { previousPrice: 10, newPrice: 20 },
    occurredAt: nowIso(),
    ...overrides,
  };
}

/** Establishes a session for the given role by mocking `POST /v1/auth/refresh`, so `RouteGuard` resolves `ready` on direct navigation. */
async function mockTenantSession(page: Page, role: string): Promise<void> {
  const token = fakeJwt({ role });
  await mockJson(page, "**/v1/auth/refresh", 200, apiSuccess(refreshResponseBody(token)));
}

test.describe("Tenant Admin Audit Log Viewer", () => {
  test("true empty state (no filters, zero rows ever) uses distinct 'no audit events yet' copy", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([]));

    await page.goto("/tenant-admin/audit-log");

    const emptyState = page.getByRole("status").filter({ hasText: "No audit events yet" });
    await expect(emptyState).toBeVisible();
    await expect(emptyState.getByRole("button", { name: "Reset filters" })).toHaveCount(0);
  });

  test("filtered-zero-results empty state uses distinct 'no events match your filters' copy with a working reset action", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));

    await page.goto("/tenant-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([]));
    await page.getByLabel("Action", { exact: true }).fill("some.unknown_action");
    await page.getByRole("button", { name: "Apply filters" }).click();

    const emptyState = page.getByRole("status").filter({ hasText: "No events match your filters" });
    await expect(emptyState).toBeVisible();
    const resetButton = emptyState.getByRole("button", { name: "Reset filters" });
    await expect(resetButton).toBeVisible();

    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));
    await resetButton.click();
    await expect(page.getByRole("table")).toBeVisible();
  });

  test("no update/delete affordance exists anywhere in the DOM", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));

    await page.goto("/tenant-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    await expect(page.getByRole("checkbox")).toHaveCount(0);
    await expect(page.getByRole("menu")).toHaveCount(0);
    await expect(page.getByRole("menuitem")).toHaveCount(0);
    for (const name of [/^Edit/i, /^Delete/i, /^Remove/i, /^Approve/i, /^Reject/i]) {
      await expect(page.getByRole("button", { name })).toHaveCount(0);
      await expect(page.getByRole("link", { name })).toHaveCount(0);
    }
  });

  test("date-range, action, and target-entity filters are reflected in the outgoing request", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));

    await page.goto("/tenant-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    const seenQueries: string[] = [];
    await page.route("**/v1/audit-log*", async (route) => {
      seenQueries.push(new URL(route.request().url()).search);
      await fulfillJson(
        route,
        200,
        apiPageSuccess([auditLogEntryBody({ action: "payment.refunded", targetEntity: "payment_refund" })])
      );
    });

    await page.getByLabel("From", { exact: true }).fill("2026-01-01");
    await page.getByLabel("To", { exact: true }).fill("2026-01-31");
    await page.getByLabel("Action", { exact: true }).fill("payment.refunded");
    await page.getByLabel("Target entity", { exact: true }).fill("payment_refund");
    await page.getByRole("button", { name: "Apply filters" }).click();

    await expect(page.getByRole("table").getByText("payment.refunded")).toBeVisible();
    expect(seenQueries.some((q) => q.includes("from=2026-01-01") && q.includes("to=2026-01-31"))).toBe(true);
    expect(seenQueries.some((q) => q.includes("action=payment.refunded"))).toBe(true);
    expect(seenQueries.some((q) => q.includes("targetEntity=payment_refund"))).toBe(true);
    // Tenant identity is resolved server-side from the authenticated session,
    // never from client input (`.claude/rules/tenancy.md`) — regression
    // guard proving the frontend never smuggles a tenant identifier into the
    // outgoing query string.
    expect(seenQueries.every((q) => !/[?&](tenantId|tenant_id)=/i.test(q))).toBe(true);
  });

  test("applying filters syncs the URL's query string, and Clear filters resets it", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));

    await page.goto("/tenant-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    await mockJson(
      page,
      "**/v1/audit-log*",
      200,
      apiPageSuccess([auditLogEntryBody({ action: "payment.refunded", targetEntity: "payment_refund" })])
    );
    await page.getByLabel("From", { exact: true }).fill("2026-01-01");
    await page.getByLabel("To", { exact: true }).fill("2026-01-31");
    await page.getByLabel("Action", { exact: true }).fill("payment.refunded");
    await page.getByLabel("Target entity", { exact: true }).fill("payment_refund");
    await page.getByRole("button", { name: "Apply filters" }).click();

    await expect(page.getByRole("table").getByText("payment.refunded")).toBeVisible();
    const appliedUrl = new URL(page.url());
    expect(appliedUrl.searchParams.get("from")).toBe("2026-01-01");
    expect(appliedUrl.searchParams.get("to")).toBe("2026-01-31");
    expect(appliedUrl.searchParams.get("action")).toBe("payment.refunded");
    expect(appliedUrl.searchParams.get("targetEntity")).toBe("payment_refund");
    // A fresh filter apply always resets to page 0, and page 0 is omitted
    // from the URL entirely (kept clean rather than always carrying `page=0`).
    expect(appliedUrl.searchParams.has("page")).toBe(false);

    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));
    await page.getByRole("button", { name: "Clear filters" }).click();
    await expect(page.getByRole("table").getByText("course.price_changed")).toBeVisible();

    const clearedUrl = new URL(page.url());
    expect(clearedUrl.search).toBe("");
  });

  test("a direct navigation to a URL with query params pre-populates the filter form and shows filtered results", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");

    const seenQueries: string[] = [];
    await page.route("**/v1/audit-log*", async (route) => {
      seenQueries.push(new URL(route.request().url()).search);
      await fulfillJson(
        route,
        200,
        apiPageSuccess([auditLogEntryBody({ action: "material.deleted", targetEntity: "material" })])
      );
    });

    await page.goto(
      "/tenant-admin/audit-log?from=2026-02-01&to=2026-02-28&action=material.deleted&targetEntity=material"
    );

    await expect(page.getByRole("table")).toBeVisible();
    await expect(page.getByRole("table").getByText("material.deleted")).toBeVisible();

    await expect(page.getByLabel("From", { exact: true })).toHaveValue("2026-02-01");
    await expect(page.getByLabel("To", { exact: true })).toHaveValue("2026-02-28");
    await expect(page.getByLabel("Action", { exact: true })).toHaveValue("material.deleted");
    await expect(page.getByLabel("Target entity", { exact: true })).toHaveValue("material");

    expect(seenQueries.some((q) => q.includes("from=2026-02-01") && q.includes("to=2026-02-28"))).toBe(true);
    expect(seenQueries.some((q) => q.includes("action=material.deleted"))).toBe(true);
    expect(seenQueries.some((q) => q.includes("targetEntity=material"))).toBe(true);
  });

  test("Next/Previous page turns are reflected in the URL and restored on reload", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/audit-log*",
      200,
      apiPageSuccess([auditLogEntryBody()], { page: 0, totalElements: 21, totalPages: 2 })
    );

    await page.goto("/tenant-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    await mockJson(
      page,
      "**/v1/audit-log*",
      200,
      apiPageSuccess([auditLogEntryBody({ action: "material.deleted" })], {
        page: 1,
        totalElements: 21,
        totalPages: 2,
      })
    );
    await page.getByRole("button", { name: "Next", exact: true }).click();
    await expect(page.getByRole("table").getByText("material.deleted")).toBeVisible();
    expect(new URL(page.url()).searchParams.get("page")).toBe("1");

    // A direct navigation to that same page-1 URL restores the same state.
    await page.goto(`/tenant-admin/audit-log?page=1`);
    await expect(page.getByRole("table").getByText("material.deleted")).toBeVisible();
    await expect(page.getByText("Page 2 of 2")).toBeVisible();
  });

  test("filter form blocks submission when 'from' is after 'to', showing a role=alert validation error and firing no request", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));

    await page.goto("/tenant-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    const seenQueries: string[] = [];
    await page.route("**/v1/audit-log*", async (route) => {
      seenQueries.push(new URL(route.request().url()).search);
      await fulfillJson(route, 200, apiPageSuccess([auditLogEntryBody()]));
    });

    await page.getByLabel("From", { exact: true }).fill("2026-02-01");
    await page.getByLabel("To", { exact: true }).fill("2026-01-01");
    await page.getByRole("button", { name: "Apply filters" }).click();

    const error = page.getByRole("alert").filter({ hasText: '"From" date must not be after "to" date.' });
    await expect(error).toBeVisible();
    expect(seenQueries).toHaveLength(0);
  });

  test("metadata accordion expands and collapses, and is keyboard-operable", async ({ page }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/audit-log*",
      200,
      apiPageSuccess([auditLogEntryBody({ metadata: { previousPrice: 10, newPrice: 20 } })])
    );

    await page.goto("/tenant-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    // The toggle's own accessible name flips between "Expand row details" and
    // "Collapse row details" as it's clicked, so it's located by position
    // (first data row's only button) rather than by name, which would stop
    // matching the same element after the first toggle.
    const rowToggle = page.getByRole("row").nth(1).getByRole("button");
    await expect(rowToggle).toHaveAttribute("aria-expanded", "false");
    await rowToggle.click();
    await expect(rowToggle).toHaveAttribute("aria-expanded", "true");

    // Scoped to the (visible, at this default viewport) desktop table — the
    // shared `expandedKeys` state also mounts an identical, CSS-hidden
    // accordion inside the mobile card markup that coexists in the DOM (see
    // `data-table.tsx`), so an unscoped locator would be ambiguous.
    const table = page.getByRole("table");
    const metadataToggle = table.getByRole("button", { name: /^Metadata \(2 fields\)/ });
    await expect(metadataToggle).toHaveAttribute("aria-expanded", "false");
    await expect(table.getByText('"previousPrice": 10')).toBeHidden();

    // Keyboard operable: Tab to focus, Enter to toggle.
    await metadataToggle.focus();
    await page.keyboard.press("Enter");
    await expect(metadataToggle).toHaveAttribute("aria-expanded", "true");
    await expect(table.getByText('"previousPrice": 10')).toBeVisible();

    // Space also toggles, and collapses it back.
    await page.keyboard.press(" ");
    await expect(metadataToggle).toHaveAttribute("aria-expanded", "false");
    await expect(table.getByText('"previousPrice": 10')).toBeHidden();
  });

  test("permission-denied state renders for a real 403 from a role holding AUDIT_LOG/VIEW but outside the interim allowlist", async ({
    page,
  }) => {
    // e.g. Finance Staff: holds the coarse `DomainArea.AUDIT_LOG`/`VIEW`
    // grant in `PermissionCheckServiceImpl`'s matrix, but the endpoint's own
    // interim allowlist (`docs/api/audit-log-management.md`, plan §21
    // decision 1) restricts `200`s to `TENANT_ADMIN`/`READ_ONLY_AUDITOR`.
    await mockTenantSession(page, "FINANCE_STAFF");
    await mockJson(
      page,
      "**/v1/audit-log*",
      403,
      apiError("FORBIDDEN", "You do not have permission to view the audit log.")
    );

    await page.goto("/tenant-admin/audit-log");

    const denied = page.getByRole("alert").filter({ hasText: "You don't have permission" });
    await expect(denied).toBeVisible();
    await expect(denied.getByRole("link", { name: "Back to your dashboard" })).toHaveAttribute(
      "href",
      "/tenant-admin/dashboard"
    );
  });

  test("Read-only Auditor (in the allowlist) can view the log", async ({ page }) => {
    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));

    await page.goto("/tenant-admin/audit-log");

    await expect(page.getByRole("table")).toBeVisible();
    await expect(page.getByRole("table").getByText("course.price_changed")).toBeVisible();
  });

  test("below md, the log renders as the DataTable's card list, not the desktop table, with no horizontal page overflow", async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 667 });
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));

    await page.goto("/tenant-admin/audit-log");

    await expect(page.getByRole("table")).toBeHidden();
    const cardList = page.getByRole("list", { name: "Audit log" });
    await expect(cardList).toBeVisible();
    await expect(cardList.getByText("course.price_changed")).toBeVisible();

    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1
    );
    expect(overflow).toBe(true);
  });

  test("true initial loading state shows the skeleton and an aria-busy 'Loading audit log…' status, then resolves", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");

    let releaseList: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseList = resolve;
    });
    await page.route("**/v1/audit-log*", async (route) => {
      await gate;
      await fulfillJson(route, 200, apiPageSuccess([auditLogEntryBody()]));
    });

    await page.goto("/tenant-admin/audit-log");

    const status = page.getByRole("status").filter({ hasText: "Loading audit log…" });
    await expect(status).toBeVisible();
    await expect(status).toHaveAttribute("aria-busy", "true");
    await expect(page.getByRole("table")).toHaveCount(0);

    releaseList?.();
    await expect(page.getByRole("table")).toBeVisible();
    await expect(status).toHaveCount(0);
  });

  test("in-flight refetch (filter re-apply) dims the table, sets aria-busy, and announces 'Updating…' before the new page resolves", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));

    await page.goto("/tenant-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    let releaseRefetch: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => {
      releaseRefetch = resolve;
    });
    await page.route("**/v1/audit-log*", async (route) => {
      await gate;
      await fulfillJson(route, 200, apiPageSuccess([auditLogEntryBody({ action: "material.deleted" })]));
    });

    await page.getByLabel("Action", { exact: true }).fill("material.deleted");
    await page.getByRole("button", { name: "Apply filters" }).click();

    const updatingRegion = page.getByRole("status").filter({ hasText: "Updating…" });
    await expect(updatingRegion).toBeAttached();
    await expect(page.locator(".opacity-60")).toBeVisible();
    await expect(page.locator('[aria-busy="true"]').first()).toBeVisible();
    // `keepPreviousData` keeps the prior rows on screen (dimmed), not a
    // skeleton, while the refetch is in flight.
    await expect(page.getByRole("table").getByText("course.price_changed")).toBeVisible();

    releaseRefetch?.();
    await expect(page.getByRole("table").getByText("material.deleted")).toBeVisible();
    await expect(page.locator(".opacity-60")).toHaveCount(0);
  });

  test("a 500 from the audit-log list renders a retryable error with the API's message, and Retry recovers", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/audit-log*",
      500,
      apiError("INTERNAL_ERROR", "Could not load the audit log.")
    );

    await page.goto("/tenant-admin/audit-log");

    const errorAlert = page.getByRole("alert").filter({ hasText: "Could not load the audit log." });
    await expect(errorAlert).toBeVisible();
    const retryButton = errorAlert.getByRole("button", { name: "Try again" });
    await expect(retryButton).toBeVisible();

    await mockJson(page, "**/v1/audit-log*", 200, apiPageSuccess([auditLogEntryBody()]));
    await retryButton.click();

    await expect(page.getByRole("table")).toBeVisible();
    // Next.js's own always-mounted route announcer also carries `role="alert"`
    // in this app shell (see `enrollment-and-course-access.spec.ts`'s
    // equivalent comment), so scope the "error is gone" assertion to the
    // error copy itself rather than a bare zero-count on the role.
    await expect(page.getByText("Could not load the audit log.")).toHaveCount(0);
  });

  test("a network failure renders the generic 'unable to reach the server' fallback, not a backend business error message", async ({
    page,
  }) => {
    // `lib/api/client.ts` wraps any thrown fetch failure (e.g. `route.abort`)
    // into a fixed-copy `ApiClientError` (`NETWORK_ERROR`) rather than
    // surfacing a raw browser error — see `tenant-registration.spec.ts`'s
    // identical assertion for a form-submit network failure. This proves the
    // same generic fallback renders for a query-driven read too, distinct
    // from the 500 test's real backend-supplied message above.
    await mockTenantSession(page, "TENANT_ADMIN");
    await page.route("**/v1/audit-log*", async (route) => {
      await route.abort("failed");
    });

    await page.goto("/tenant-admin/audit-log");

    const errorAlert = page.getByRole("alert").filter({ hasText: "Unable to reach the server" });
    await expect(errorAlert).toBeVisible();
    await expect(page.getByText("Could not load the audit log.")).toHaveCount(0);
    await expect(errorAlert.getByRole("button", { name: "Try again" })).toBeVisible();
  });

  test("'no more results' empty state (paged past the end, no filters) renders distinct copy with a working 'Go to previous page' action", async ({
    page,
  }) => {
    await mockTenantSession(page, "TENANT_ADMIN");
    await mockJson(
      page,
      "**/v1/audit-log*",
      200,
      apiPageSuccess([auditLogEntryBody()], { page: 0, totalElements: 21, totalPages: 2 })
    );

    await page.goto("/tenant-admin/audit-log");
    await expect(page.getByRole("table")).toBeVisible();

    await mockJson(
      page,
      "**/v1/audit-log*",
      200,
      apiPageSuccess([], { page: 1, totalElements: 21, totalPages: 2 })
    );
    await page.getByRole("button", { name: "Next", exact: true }).click();

    const emptyState = page.getByRole("status").filter({ hasText: "No more results" });
    await expect(emptyState).toBeVisible();
    // Distinct from the other two empty-state variants' copy.
    await expect(page.getByText("No audit events yet")).toHaveCount(0);
    await expect(page.getByText("No events match your filters")).toHaveCount(0);

    const backButton = emptyState.getByRole("button", { name: "Go to previous page" });
    await expect(backButton).toBeVisible();

    await mockJson(
      page,
      "**/v1/audit-log*",
      200,
      apiPageSuccess([auditLogEntryBody()], { page: 0, totalElements: 21, totalPages: 2 })
    );
    await backButton.click();
    await expect(page.getByRole("table")).toBeVisible();
  });
});

test.describe("Audit Log nav visibility", () => {
  async function mockDashboardReads(page: Page) {
    await mockJson(page, "**/v1/students", 200, apiSuccess([]));
    await mockJson(page, "**/api/v1/ledger/dashboard*", 200, apiPageSuccess([]));
    await page.route("**/api/v1/courses*", async (route) => {
      await fulfillJson(route, 200, apiPageSuccess([]));
    });
  }

  test("Tenant Admin and Read-only Auditor see the Audit Log nav entry; an unrelated staff role does not", async ({
    page,
  }) => {
    await mockDashboardReads(page);

    await mockTenantSession(page, "TENANT_ADMIN");
    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByRole("link", { name: "Audit Log" })).toHaveAttribute("href", "/tenant-admin/audit-log");

    await mockTenantSession(page, "READ_ONLY_AUDITOR");
    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByRole("link", { name: "Audit Log" })).toHaveAttribute("href", "/tenant-admin/audit-log");

    await mockTenantSession(page, "FINANCE_STAFF");
    await page.goto("/tenant-admin/dashboard");
    await expect(page.getByRole("link", { name: "Audit Log" })).toHaveCount(0);
  });
});
