import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for `tenant-management`'s Platform Admin
 * tenant approval-queue endpoints (`/api/v1/platform-admin/tenants/**`,
 * `hasRole('PLATFORM_ADMIN')` only). Follows `lib/api/audit-log.ts`'s
 * conventions exactly (`/v1/...` paths, `authorizedFetch("platform-admin",
 * ...)`, a query-keys factory, `keepPreviousData` for page/filter turns).
 */

/**
 * Mirrors the backend's `com.lms.tenantmanagement.api.TenantStatus` Java enum
 * as Jackson serializes it on the wire (`Enum#name()`, e.g. `"ACTIVE"`) —
 * confirmed by `PlatformAdminTenantControllerIntegrationTest` deserializing
 * `TenantSummaryResponse`/`TenantDetailResponse.status()` as the enum itself
 * and filtering via `?status=PENDING_APPROVAL`. This is deliberately NOT the
 * lowercase `tenant.status` CHECK-constraint column value
 * (`pending_approval | trial | ...`, see
 * `backend/src/main/resources/db/migration/V2__create_tenant_table.sql`) —
 * that lowercase form is a separate, persistence-only mapping
 * (`TenantStatusConverter`) that never reaches this JSON boundary for these
 * two response types. (`TenantRegistrationResponse.status` is a different,
 * unrelated endpoint that does intentionally serialize the lowercase form —
 * don't conflate the two.)
 * Canonical definition lives here (the API layer) rather than in
 * `status-badge.tsx` (a feature component under `app/`) — `lib/` must not
 * depend on `app/`, only the reverse; `status-badge.tsx` imports and
 * re-exports this type instead.
 */
export type TenantStatus =
  | "PENDING_APPROVAL"
  | "TRIAL"
  | "ACTIVE"
  | "SUSPENDED"
  | "CANCELLED"
  | "REJECTED";

/** Mirrors `TenantSummaryResponse`. */
export interface TenantSummaryResponse {
  id: string;
  name: string;
  subdomain: string;
  status: TenantStatus;
  requestedPlan: string;
  createdAt: string;
}

/** Mirrors `TenantDetailResponse`. */
export interface TenantDetailResponse {
  id: string;
  name: string;
  subdomain: string;
  status: TenantStatus;
  requestedPlan: string;
  contactName: string | null;
  contactEmail: string | null;
  contactPhone: string | null;
  createdAt: string;
}

export interface PlatformAdminTenantsParams {
  status?: TenantStatus;
  page?: number;
  size?: number;
}

export const platformAdminTenantKeys = {
  all: ["platform-admin", "tenants"] as const,
  lists: () => [...platformAdminTenantKeys.all, "list"] as const,
  list: (params?: PlatformAdminTenantsParams) =>
    [...platformAdminTenantKeys.lists(), params ?? {}] as const,
  details: () => [...platformAdminTenantKeys.all, "detail"] as const,
  detail: (id: string) => [...platformAdminTenantKeys.details(), id] as const,
};

function buildTenantsQuery(params?: PlatformAdminTenantsParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  if (params?.status) search.set("status", params.status);
  return `?${search.toString()}`;
}

/** `GET /api/v1/platform-admin/tenants` — status-filterable, paginated, default sort `createdAt,desc`. */
export function usePlatformAdminTenants(params?: PlatformAdminTenantsParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildTenantsQuery(params);
  return useQuery({
    queryKey: platformAdminTenantKeys.list(params),
    queryFn: () =>
      authorizedFetch<PageResponse<TenantSummaryResponse>>(
        "platform-admin",
        `/v1/platform-admin/tenants${queryString}`
      ),
    placeholderData: keepPreviousData,
  });
}

/** `GET /api/v1/platform-admin/tenants/{id}` — `404` (`NOT_FOUND`) if unknown. */
export function usePlatformAdminTenantDetail(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: platformAdminTenantKeys.detail(id),
    queryFn: () =>
      authorizedFetch<TenantDetailResponse>(
        "platform-admin",
        `/v1/platform-admin/tenants/${id}`
      ),
  });
}

/**
 * `POST /api/v1/platform-admin/tenants/{id}/approve` — no request body. `409`
 * (`CONFLICT`, "Tenant is not pending approval") if the tenant isn't
 * currently `pending_approval` — the caller (`approve-tenant-dialog.tsx`)
 * surfaces this inline, dialog kept open, never as a generic error.
 */
export function useApproveTenant(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<TenantDetailResponse>(
        "platform-admin",
        `/v1/platform-admin/tenants/${id}/approve`,
        { method: "POST" }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: platformAdminTenantKeys.lists() });
      queryClient.invalidateQueries({ queryKey: platformAdminTenantKeys.detail(id) });
    },
  });
}

/** `POST /api/v1/platform-admin/tenants/{id}/reject` — same shape/semantics as approve. */
export function useRejectTenant(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<TenantDetailResponse>(
        "platform-admin",
        `/v1/platform-admin/tenants/${id}/reject`,
        { method: "POST" }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: platformAdminTenantKeys.lists() });
      queryClient.invalidateQueries({ queryKey: platformAdminTenantKeys.detail(id) });
    },
  });
}
