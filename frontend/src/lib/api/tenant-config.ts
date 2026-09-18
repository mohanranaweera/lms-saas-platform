"use client";

import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for `tenant-management`'s Wave-1 Tenant
 * Configuration Framework
 * (`backend/.../tenantmanagement/web/TenantConfigController.java`,
 * `GET/PUT /api/v1/tenant-config/{domain}`). Follows `lib/api/audit-log.ts`'s
 * exact conventions (`/v1/...` paths — `NEXT_PUBLIC_API_BASE_URL` already
 * ends in `/api` — `authorizedFetch("tenant", ...)`, a query-keys factory).
 *
 * Only `GENERAL`/`BRANDING` currently have any registered properties per
 * `ConfigPropertyRegistry` — every other domain in `TenantConfigDomain`
 * below resolves to a real `200` with an empty array, never `404`. No screen
 * is built for those 15 domains in this wave (see
 * `components/layout/nav/tenant-admin-nav.tsx`'s own doc comment); this
 * client still types the full enum so a later wave adding a screen for one
 * of them doesn't need to touch this file's domain type.
 *
 * `GET /api/v1/public/tenant-config/branding` (no auth, tenant resolved by
 * host) is a distinct, unauthenticated endpoint for the public tenant portal
 * — out of scope for this wave's frontend (no public-portal theming screen
 * exists yet) and deliberately not added here; do not conflate it with
 * `useTenantConfigDomain("BRANDING")` below, which is the authenticated
 * Tenant Admin/Read-only Auditor read of the same domain's raw property
 * rows.
 */

/**
 * Mirrors `ConfigDomain`
 * (`backend/.../tenantmanagement/api/ConfigDomain.java`) exactly — all 17
 * values.
 */
export type TenantConfigDomain =
  | "GENERAL"
  | "BRANDING"
  | "ACADEMIC"
  | "STUDENT"
  | "TEACHER"
  | "COURSE"
  | "PAYMENT"
  | "FINANCE"
  | "ATTENDANCE"
  | "EXAM"
  | "CONTENT"
  | "VIDEO"
  | "NOTIFICATION"
  | "SECURITY"
  | "DEVICE"
  | "DOMAIN"
  | "INTEGRATION";

/** Mirrors `ConfigValueType`. No property in this wave uses `NUMBER`/`BOOLEAN`/`JSON` — every real `GENERAL`/`BRANDING` key is `STRING`. */
export type TenantConfigValueType = "STRING" | "NUMBER" | "BOOLEAN" | "JSON";

/**
 * Mirrors `ConfigPropertyResponse` field-for-field. `value` is `null` for a
 * `sensitive` property regardless of caller — the backend never returns the
 * raw persisted value for one (see `ConfigPropertyResponse`'s own doc
 * comment). Neither `GENERAL` nor `BRANDING` currently defines a sensitive
 * key, but a form built against this type must not assume `sensitive` is
 * always `false`.
 */
export interface TenantConfigProperty {
  key: string;
  value: unknown;
  type: TenantConfigValueType;
  defaultValue: unknown;
  sensitive: boolean;
}

export const tenantConfigKeys = {
  all: ["tenant-config"] as const,
  domain: (domain: TenantConfigDomain) => [...tenantConfigKeys.all, domain] as const,
};

/**
 * `GET /api/v1/tenant-config/{domain}` — `BRANDING_SETTINGS`/`VIEW`
 * (Tenant Admin, Read-only Auditor); every other role gets a real `403`,
 * surfaced via `QueryStateBoundary` exactly like any other permission-denied
 * case. `lib/auth/permissions.ts#canViewInstituteConfig` is UX convenience
 * only (nav-entry visibility), never the enforcement point — this hook
 * issues the real request unconditionally regardless of the caller's
 * client-known role.
 */
export function useTenantConfigDomain(domain: TenantConfigDomain) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: tenantConfigKeys.domain(domain),
    queryFn: () => authorizedFetch<TenantConfigProperty[]>("tenant", `/v1/tenant-config/${domain}`),
  });
}

/**
 * `PUT /api/v1/tenant-config/{domain}` — `BRANDING_SETTINGS`/`CREATE_EDIT`
 * (Tenant Admin only, server-enforced — Read-only Auditor gets a real `403`
 * here despite holding `VIEW`). `changes` must contain only the keys the
 * caller actually changed
 * (`TenantConfigController#updateDomain`'s `Map<String, Object>` body
 * contract — see `lib/validation/tenant-config.ts#toChangedConfigEntries`
 * for how a form computes that diff), never every key in the domain
 * unconditionally. On success, writes this domain's cached list directly
 * from the response (already the authoritative post-write state) rather
 * than just invalidating, so the calling form's "current values" reflect
 * exactly what the backend persisted without an extra round-trip.
 */
export function useUpdateTenantConfigDomain(domain: TenantConfigDomain) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (changes: Record<string, unknown>) =>
      authorizedFetch<TenantConfigProperty[]>("tenant", `/v1/tenant-config/${domain}`, {
        method: "PUT",
        body: JSON.stringify(changes),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(tenantConfigKeys.domain(domain), data);
    },
  });
}

export interface GeneralAndBrandingConfig {
  general: TenantConfigProperty[];
  branding: TenantConfigProperty[];
}

interface CombinedConfigResult {
  status: "pending" | "error" | "success";
  data: GeneralAndBrandingConfig | undefined;
  error: unknown;
  /** Re-runs both underlying queries; the resolved value isn't meaningful, mirroring `lib/api/tenant-overview.ts#useTenantCourseCounts`'s identical combined-result shape. */
  refetch: () => Promise<unknown>;
}

/**
 * Fires `GET /api/v1/tenant-config/GENERAL` and `.../BRANDING` in parallel,
 * combined into one `QueryStateBoundary`-compatible result — the Branding
 * settings page's live preview panel needs `institute_name` from `GENERAL`
 * alongside `BRANDING`'s own properties (see
 * `settings/branding/branding-config-form.tsx`). Both domains are gated by
 * the identical `BRANDING_SETTINGS`/`VIEW` grant server-side, so the two
 * requests always succeed or fail together for a given role in practice —
 * this combinator still handles them independently (mirroring
 * `useTenantCourseCounts`'s pattern) rather than assuming that.
 */
export function useGeneralAndBrandingConfig(): CombinedConfigResult {
  const { authorizedFetch } = useAuth();

  return useQueries({
    queries: [
      {
        queryKey: tenantConfigKeys.domain("GENERAL"),
        queryFn: () => authorizedFetch<TenantConfigProperty[]>("tenant", "/v1/tenant-config/GENERAL"),
      },
      {
        queryKey: tenantConfigKeys.domain("BRANDING"),
        queryFn: () => authorizedFetch<TenantConfigProperty[]>("tenant", "/v1/tenant-config/BRANDING"),
      },
    ],
    combine: (results): CombinedConfigResult => {
      const [generalResult, brandingResult] = results;
      const hasError = generalResult.status === "error" || brandingResult.status === "error";
      const isPending = generalResult.status === "pending" || brandingResult.status === "pending";
      const status: CombinedConfigResult["status"] = hasError
        ? "error"
        : isPending
          ? "pending"
          : "success";

      const data: GeneralAndBrandingConfig | undefined =
        status === "success" && generalResult.data && brandingResult.data
          ? { general: generalResult.data, branding: brandingResult.data }
          : undefined;

      return {
        status,
        data,
        error: generalResult.error ?? brandingResult.error ?? null,
        refetch: () => {
          void generalResult.refetch();
          return brandingResult.refetch();
        },
      };
    },
  });
}
