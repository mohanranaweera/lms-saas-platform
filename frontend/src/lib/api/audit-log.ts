import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hook for `audit-log-management`'s MVP-019
 * "Audit Log Viewer" (AUDIT-3) read endpoint (`GET /api/v1/audit-log` — see
 * `docs/api/audit-log-management.md`). Follows `lib/api/attendance.ts`'s
 * conventions exactly (`/v1/...` paths, `authorizedFetch("tenant", ...)`, a
 * query-keys factory, `keepPreviousData` for page/filter turns).
 *
 * This is a read-only surface: there is no mutation hook here, and none
 * should be added — `audit_log` has no `PUT`/`PATCH`/`DELETE` route for any
 * role (append-only at the backend's schema/repository level).
 */

/** Mirrors `AuditLogEntryResponse` field-for-field (`docs/api/audit-log-management.md`). */
export interface AuditLogEntryResponse {
  id: string;
  actorId: string;
  /** Best-effort; `null` if the actor id no longer resolves to a `tenant_user` row in the caller's own tenant. */
  actorDisplayName: string | null;
  action: string;
  targetEntity: string;
  targetId: string;
  /** Populated only for actions that carry one (currently: `payment.refunded`). */
  reason: string | null;
  /** Action-specific, nullable. Render as a `<pre>`-formatted block, never an inline stringified blob. */
  metadata: Record<string, unknown> | null;
  occurredAt: string;
}

/**
 * `GET /api/v1/audit-log` query params. `action`/`targetEntity` are exact-
 * match strings — the backend accepts any value and simply returns zero rows
 * for one that doesn't match any row, never a validation error (this is
 * deliberate anti-enumeration behavior, see the contract doc). `from`/`to`
 * must be full ISO-8601 instants, not bare `YYYY-MM-DD` dates — a
 * day-granularity filter UI must convert via
 * `lib/validation/audit-log.ts#toAuditLogQueryParams` before calling this hook.
 */
export interface AuditLogSearchParams {
  page?: number;
  size?: number;
  sort?: string;
  from?: string;
  to?: string;
  action?: string;
  targetEntity?: string;
}

export const auditLogKeys = {
  all: ["audit-log"] as const,
  searchAll: () => [...auditLogKeys.all, "search"] as const,
  search: (params?: AuditLogSearchParams) => [...auditLogKeys.searchAll(), params ?? {}] as const,
};

function buildAuditLogSearchQuery(params?: AuditLogSearchParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  search.set("sort", params?.sort ?? "occurredAt,desc");
  if (params?.from) search.set("from", params.from);
  if (params?.to) search.set("to", params.to);
  if (params?.action) search.set("action", params.action);
  if (params?.targetEntity) search.set("targetEntity", params.targetEntity);
  return `?${search.toString()}`;
}

/**
 * `GET /api/v1/audit-log` — server-enforced in two layers (see the contract
 * doc's "Authorization model"): the existing `DomainArea.AUDIT_LOG`/`VIEW`
 * grant, AND an interim allowlist restricting `200` responses to
 * `TENANT_ADMIN`/`READ_ONLY_AUDITOR` only. This hook issues the real request
 * unconditionally regardless of the caller's client-known role — a role
 * holding the coarse grant but outside the allowlist (e.g. `FINANCE_STAFF`)
 * still gets a real `403`, surfaced via `QueryStateBoundary` exactly like any
 * other permission-denied case. `lib/auth/permissions.ts#canViewAuditLog` is
 * UX convenience only (nav-entry visibility), never the enforcement point.
 */
export function useAuditLogSearch(params?: AuditLogSearchParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildAuditLogSearchQuery(params);
  return useQuery({
    queryKey: auditLogKeys.search(params),
    queryFn: () =>
      authorizedFetch<PageResponse<AuditLogEntryResponse>>("tenant", `/v1/audit-log${queryString}`),
    placeholderData: keepPreviousData,
  });
}

/**
 * Mirrors `StudentActivityResponse`/`TeacherActivityResponse` (Wave 3) —
 * these two backend DTOs are field-for-field identical to each other, so one
 * shared type covers both `GET /v1/students/{id}/activity` and `GET
 * /v1/teachers/{id}/activity`. Deliberately NOT the same shape as
 * `AuditLogEntryResponse` above (no `targetEntity`/`targetId` — both are
 * implicit from which endpoint/id was called).
 */
export interface ActivityEntryResponse {
  id: string;
  actorId: string;
  actorDisplayName: string | null;
  action: string;
  reason: string | null;
  metadata: Record<string, unknown> | null;
  occurredAt: string;
}

export interface ActivityListParams {
  page?: number;
  size?: number;
}

function buildActivityQuery(params?: ActivityListParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  return `?${search.toString()}`;
}

export const activityKeys = {
  studentAll: (studentId: string) => ["students", studentId, "activity"] as const,
  student: (studentId: string, params?: ActivityListParams) =>
    [...activityKeys.studentAll(studentId), params ?? {}] as const,
  teacherAll: (teacherId: string) => ["teachers", teacherId, "activity"] as const,
  teacher: (teacherId: string, params?: ActivityListParams) =>
    [...activityKeys.teacherAll(teacherId), params ?? {}] as const,
};

/**
 * `GET /v1/students/{id}/activity` (Wave 3) — `isAuthenticated()` server-side
 * (coarser than the generic `/v1/audit-log` viewer's `AUDIT_LOG`/`VIEW`
 * allowlist), backed by the same append-only `AuditLogQueryService`, filtered
 * to `targetEntity='student_profile' AND targetId={id}`.
 */
export function useStudentActivity(studentId: string, params?: ActivityListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildActivityQuery(params);
  return useQuery({
    queryKey: activityKeys.student(studentId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<ActivityEntryResponse>>(
        "tenant",
        `/v1/students/${studentId}/activity${queryString}`
      ),
    enabled: studentId.length > 0,
    placeholderData: keepPreviousData,
  });
}

/** `GET /v1/teachers/{id}/activity` (Wave 3) — same contract as `useStudentActivity` above, for `teacher_profile`. */
export function useTeacherActivity(teacherId: string, params?: ActivityListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildActivityQuery(params);
  return useQuery({
    queryKey: activityKeys.teacher(teacherId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<ActivityEntryResponse>>(
        "tenant",
        `/v1/teachers/${teacherId}/activity${queryString}`
      ),
    enabled: teacherId.length > 0,
    placeholderData: keepPreviousData,
  });
}
