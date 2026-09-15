import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";
import type { LedgerEntryType } from "./ledger";

/**
 * Typed client + React Query hooks for `ledger-settlement-management`'s
 * Platform Admin cross-tenant payment dashboard endpoints
 * (`/api/v1/platform-admin/payments/**`, `hasRole('PLATFORM_ADMIN')` only).
 * Follows `lib/api/ledger.ts`'s conventions (ledger is the source of truth
 * for "what was actually paid/refunded", per `.claude/rules/payments.md`
 * §2/§4) plus `lib/api/audit-log.ts`'s query-key-factory/`keepPreviousData`
 * pattern.
 *
 * Read-only surface: there is no mutation hook here, and none should be
 * added — no refund/adjustment action is reachable from this dashboard (see
 * module plan §17); such actions stay on the existing tenant-scoped
 * payment/refund endpoints.
 */

/** Mirrors `PlatformLedgerEntryResponse`. */
export interface PlatformLedgerEntryResponse {
  id: string;
  tenantId: string;
  /** `null` if the tenant id no longer resolves — render a `shortId` fallback, never blank. */
  tenantName: string | null;
  orderId: string;
  paymentId: string | null;
  entryType: LedgerEntryType;
  amount: number;
  reversesEntryId: string | null;
  createdAt: string;
}

export interface PlatformPaymentsParams {
  page?: number;
  size?: number;
}

export const platformAdminPaymentsKeys = {
  all: ["platform-admin", "payments"] as const,
  dashboard: (params?: PlatformPaymentsParams) =>
    [...platformAdminPaymentsKeys.all, "dashboard", params ?? {}] as const,
  tenant: (tenantId: string, params?: PlatformPaymentsParams) =>
    [...platformAdminPaymentsKeys.all, "tenant", tenantId, params ?? {}] as const,
};

function buildPaymentsQuery(params?: PlatformPaymentsParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  return `?${search.toString()}`;
}

/**
 * `GET /api/v1/platform-admin/payments/dashboard` — cross-tenant, paginated,
 * default sort `createdAt,desc`. No filter params exist on this endpoint.
 */
export function usePlatformPaymentsDashboard(params?: PlatformPaymentsParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildPaymentsQuery(params);
  return useQuery({
    queryKey: platformAdminPaymentsKeys.dashboard(params),
    queryFn: () =>
      authorizedFetch<PageResponse<PlatformLedgerEntryResponse>>(
        "platform-admin",
        `/v1/platform-admin/payments/dashboard${queryString}`
      ),
    placeholderData: keepPreviousData,
  });
}

/**
 * `GET /api/v1/platform-admin/payments/tenants/{tenantId}` — single-tenant
 * drill-down; `404` (`NOT_FOUND`) if `tenantId` doesn't resolve to a real
 * tenant.
 */
export function usePlatformPaymentsTenantDrillDown(
  tenantId: string,
  params?: PlatformPaymentsParams
) {
  const { authorizedFetch } = useAuth();
  const queryString = buildPaymentsQuery(params);
  return useQuery({
    queryKey: platformAdminPaymentsKeys.tenant(tenantId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<PlatformLedgerEntryResponse>>(
        "platform-admin",
        `/v1/platform-admin/payments/tenants/${tenantId}${queryString}`
      ),
    placeholderData: keepPreviousData,
  });
}
