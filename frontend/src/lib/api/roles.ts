"use client";

import { useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hook for `identity-access-service`'s read-only
 * role catalog endpoint (RBAC-1, `GET /api/v1/roles`,
 * `backend/.../identityaccessservice/web/RoleCatalogController.java`).
 *
 * Read-only surface — RBAC grants are code-fixed, not tenant-editable (per
 * this endpoint's own doc comment: it backs an "assign role" dropdown, not a
 * permission-matrix editor) — there is no mutation hook here, and none
 * should be added.
 */

/** Mirrors `RoleSummary` field-for-field. Already filtered server-side to `RoleScope.TENANT` rows only (no `PLATFORM`-scope entries ever appear here). */
export interface RoleSummary {
  code: string;
  scope: string;
  displayName: string;
  description: string | null;
  portalRouteGroup: string;
  selfRegisters: boolean;
  isProvisional: boolean;
}

const rolesListKey = ["roles"] as const;

/**
 * `GET /v1/roles` — `STAFF_AND_ROLES`/`VIEW` (Tenant Admin, Read-only
 * Auditor); every other role gets a real `403`, surfaced via
 * `QueryStateBoundary` exactly like any other permission-denied case.
 */
export function useAssignableRoles() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: rolesListKey,
    queryFn: () => authorizedFetch<RoleSummary[]>("tenant", "/v1/roles"),
  });
}
