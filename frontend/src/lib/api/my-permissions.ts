"use client";

import { useCallback, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "@/lib/api/error";
import { can as canWith, isCapabilityDrivenRole, isCapabilityRole, type CapabilityKey } from "@/lib/auth/capabilities";

/**
 * `GET /v1/me/permissions` (STAFF-MGMT-02 §2.5, `MyPermissionsController`):
 * the caller's live role and effective capability keys. UI gating only - the
 * backend re-checks every operation, and a revoked grant is denied on the very
 * next request regardless of what this cache says.
 */
export interface MyPermissionsResponse {
  role: string;
  capabilities: string[];
  permissionVersion: number;
}

export const myPermissionsKey = ["me", "permissions"] as const;

/**
 * Refetched on window focus and every 60 s; `query-provider.tsx` also
 * invalidates it after any 403, so a grant revoked by an administrator is
 * reflected in the UI shortly after.
 */
export function useMyPermissions(options: { enabled?: boolean } = {}) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: myPermissionsKey,
    queryFn: () => authorizedFetch<MyPermissionsResponse>("tenant", "/v1/me/permissions"),
    // A 4xx won't change on retry; a network failure is retried once.
    retry: (failureCount, error) => failureCount < 1 && !(isApiClientError(error) && error.status < 500),
    enabled: options.enabled ?? true,
    staleTime: 15_000,
    refetchOnWindowFocus: true,
    refetchInterval: 60_000,
  });
}

/**
 * Capability gate for the current session. `has(cap)` is strict (the key must
 * be in my effective capabilities; used by Staff Management, whose gates are
 * capability-native for every role). `can(cap, legacyCheck)` defers to the
 * existing role helper for Tenant Admin / Teacher and uses capabilities for
 * OPERATOR, MANAGER and the seven seeded sub-roles - see
 * `lib/auth/capabilities.ts#can`.
 *
 * `fetchForAllRoles` fetches `/v1/me/permissions` even for Tenant Admin (the
 * staff page needs it); otherwise it is only fetched for capability-driven
 * roles. `isCapabilityRole` is OPERATOR / MANAGER only (nothing by default);
 * `isCapabilityDriven` adds the seeded sub-roles.
 */
export function useCapabilities(options: { fetchForAllRoles?: boolean } = {}) {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const capabilityRole = isCapabilityRole(role);
  const capabilityDriven = isCapabilityDrivenRole(role);
  const query = useMyPermissions({ enabled: Boolean(session) && (options.fetchForAllRoles || capabilityDriven) });
  const set = useMemo<ReadonlySet<CapabilityKey> | null>(
    () => (query.data ? new Set(query.data.capabilities) : null),
    [query.data]
  );

  // Sticky across refetches (a background refetch of a failed query must not
  // flip the UI back to "loading" and remount/unmount gated controls).
  const capabilitiesFailed = query.errorUpdatedAt > 0 && !query.data;
  const has = useCallback((capability: CapabilityKey) => set?.has(capability) ?? false, [set]);
  const can = useCallback(
    (capability: CapabilityKey | readonly CapabilityKey[], legacyCheck: boolean) =>
      canWith(capability, role, legacyCheck, set, { capabilitiesFailed }),
    [role, set, capabilitiesFailed]
  );

  return {
    role,
    isCapabilityRole: capabilityRole,
    isCapabilityDriven: capabilityDriven,
    query,
    loaded: set !== null,
    has,
    can,
  };
}
