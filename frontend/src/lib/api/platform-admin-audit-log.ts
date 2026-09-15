import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for `audit-log-management`'s Platform
 * Admin platform-level audit-log endpoints
 * (`/api/v1/platform-admin/audit-log/**`, `hasRole('PLATFORM_ADMIN')` only).
 * Follows `lib/api/audit-log.ts`'s conventions exactly, but this query path
 * is structurally distinct from `AuditLogQueryService`'s tenant-scoped,
 * `ADR-014`-allowlisted query (different endpoint, different response shape
 * — every row here carries its own `tenantId`/`tenantName`).
 *
 * Read-only: `audit_log` has no `PUT`/`PATCH`/`DELETE` route for any role —
 * no mutation hook exists here, and none should be added.
 */

/** Mirrors `PlatformAuditLogEntryResponse`. */
export interface PlatformAuditLogEntryResponse {
  id: string;
  tenantId: string;
  /** `null` if the tenant id no longer resolves — render a `shortId` fallback, never blank. */
  tenantName: string | null;
  actorId: string;
  action: string;
  targetEntity: string;
  targetId: string;
  reason: string | null;
  metadata: Record<string, unknown> | null;
  occurredAt: string;
}

/**
 * `GET /api/v1/platform-admin/audit-log` query params. `from`/`to` must be
 * full ISO-8601 instants (see `lib/validation/platform-audit-log.ts`'s
 * `toPlatformAuditLogQueryParams`). `action` is an exact-match string. There
 * is deliberately no `targetEntity` param here — that's a tenant-scoped-only
 * filter on the separate `useAuditLogSearch` endpoint, not this one.
 */
export interface PlatformAuditLogSearchParams {
  page?: number;
  size?: number;
  from?: string;
  to?: string;
  action?: string;
}

export const platformAuditLogKeys = {
  all: ["platform-admin", "audit-log"] as const,
  searches: () => [...platformAuditLogKeys.all, "search"] as const,
  search: (params?: PlatformAuditLogSearchParams) =>
    [...platformAuditLogKeys.searches(), params ?? {}] as const,
  tenantSearches: (tenantId: string) =>
    [...platformAuditLogKeys.all, "tenant-search", tenantId] as const,
  tenantSearch: (tenantId: string, params?: PlatformAuditLogSearchParams) =>
    [...platformAuditLogKeys.tenantSearches(tenantId), params ?? {}] as const,
};

function buildPlatformAuditLogQuery(params?: PlatformAuditLogSearchParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  if (params?.from) search.set("from", params.from);
  if (params?.to) search.set("to", params.to);
  if (params?.action) search.set("action", params.action);
  return `?${search.toString()}`;
}

/**
 * `GET /api/v1/platform-admin/audit-log` — platform-wide, filterable by date
 * range/action, paginated. `from` after `to` → `400 VALIDATION_ERROR`.
 */
export function usePlatformAuditLog(params?: PlatformAuditLogSearchParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildPlatformAuditLogQuery(params);
  return useQuery({
    queryKey: platformAuditLogKeys.search(params),
    queryFn: () =>
      authorizedFetch<PageResponse<PlatformAuditLogEntryResponse>>(
        "platform-admin",
        `/v1/platform-admin/audit-log${queryString}`
      ),
    placeholderData: keepPreviousData,
  });
}

/**
 * `GET /api/v1/platform-admin/audit-log/tenants/{tenantId}` — single-tenant
 * drill-down; `404` if `tenantId` doesn't resolve to a real tenant.
 */
export function usePlatformAuditLogTenantDrillDown(
  tenantId: string,
  params?: PlatformAuditLogSearchParams
) {
  const { authorizedFetch } = useAuth();
  const queryString = buildPlatformAuditLogQuery(params);
  return useQuery({
    queryKey: platformAuditLogKeys.tenantSearch(tenantId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<PlatformAuditLogEntryResponse>>(
        "platform-admin",
        `/v1/platform-admin/audit-log/tenants/${tenantId}${queryString}`
      ),
    placeholderData: keepPreviousData,
  });
}
