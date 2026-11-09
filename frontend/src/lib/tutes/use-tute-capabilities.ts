"use client";

import { useCallback } from "react";
import { useCapabilities } from "@/lib/api/my-permissions";

/** TUTE-01 capability keys (`Capability.key()` verbatim, spec §8). */
export const TUTE_CAP = {
  VIEW_TUTES: "TUTE_VIEW_TUTES",
  VIEW_TUTE: "TUTE_VIEW_TUTE",
  CREATE: "TUTE_CREATE",
  UPDATE: "TUTE_UPDATE",
  ENROLL: "TUTE_ENROLL",
  UNROLL: "TUTE_UNROLL",
  UPDATE_STATUS: "TUTE_UPDATE_STATUS",
  DELETE: "TUTE_DELETE",
  DISPATCH_VIEW: "TUTE_DISPATCH_VIEW",
  DISPATCH_PRINT_LABELS: "TUTE_DISPATCH_PRINT_LABELS",
} as const;

export type TuteCapability = (typeof TUTE_CAP)[keyof typeof TUTE_CAP];

/**
 * UX-only gate for Tute screens. Tutes are capability-native for every role
 * (Tenant Admin holds all by default; Operator, Manager, the seeded sub-roles
 * and Teacher only what a Tenant Admin grants), so the answer comes from the
 * caller's effective capabilities (`GET /v1/me/permissions`, fetched for all
 * roles, refetched on focus / every 60 s and after any 403). While loading,
 * nothing is shown (fails closed); if the request fails, only Tenant Admin
 * falls back to its static default (all). The backend's
 * `@RequiresCapability` + service checks are the enforcement.
 */
export function useTuteCapabilities() {
  const { role, has, query } = useCapabilities({ fetchForAllRoles: true });
  const failed = query.errorUpdatedAt > 0 && !query.data;
  const can = useCallback(
    (capability: TuteCapability) => has(capability) || (failed && role === "TENANT_ADMIN"),
    [has, failed, role]
  );
  return { can, loading: query.isPending && !failed, role };
}
