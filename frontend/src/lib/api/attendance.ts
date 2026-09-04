import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for `attendance-management`'s MVP-016
 * "Attendance" endpoints (`/api/v1/attendance/**` — see
 * `AttendanceController`). Follows `lib/api/enrollments.ts`'s conventions
 * exactly (`/v1/...` paths, every call through
 * `useAuth().authorizedFetch("tenant", ...)`, a query-keys factory object,
 * `onSuccess` cache invalidation on mutations).
 *
 * Session-equivalent scope at this MVP is `course_lesson.id` — there is no
 * separate `class_session` table (see the plan's boxed note in §7). Callers
 * resolve a `sessionId` via the existing course→module→lesson cascade
 * (`useCourseModules`/`useCourseLessons` in `lib/api/courses.ts`) and label
 * the lesson selector "Session" in the UI.
 */

export type AttendanceStatus = "PRESENT" | "ABSENT" | "LATE";

/** Mirrors `AttendanceRosterEntryResponse` — `status` is `null` when the student has not yet been marked for this session. */
export interface AttendanceRosterEntryResponse {
  studentId: string;
  status: AttendanceStatus | null;
}

/** Mirrors `AttendanceRosterResponse` — `GET /api/v1/attendance/sessions/{sessionId}/roster`'s response body. */
export interface AttendanceRosterResponse {
  courseId: string;
  sessionId: string;
  roster: AttendanceRosterEntryResponse[];
}

/** Mirrors `AttendanceMarkEntryRequest` — one entry of `POST .../records`'s `marks` array. `status` is required (never omit a row you don't intend to change; see `useMarkAttendance`). */
export interface AttendanceMarkEntryRequest {
  studentId: string;
  status: AttendanceStatus;
}

/** Mirrors `MarkAttendanceRequest` — `POST /api/v1/attendance/sessions/{sessionId}/records`'s request body (1–500 entries). */
export interface MarkAttendanceRequestBody {
  marks: AttendanceMarkEntryRequest[];
}

/** Mirrors `AttendanceRecordResponse` field-for-field. */
export interface AttendanceRecordResponse {
  id: string;
  courseId: string;
  sessionId: string;
  studentId: string;
  status: AttendanceStatus;
  markedBy: string;
  markedAt: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * Mirrors `AttendanceMarkResultResponse` — one per-row batch outcome from
 * `POST .../records` (plan §13's batch-partial marking contract). Exactly
 * one of `record`/`reason` is non-null, matching `success`. Callers must
 * surface a `success: false` row individually (its `reason`), never treat
 * the whole submit as failed just because one row was rejected, and never
 * treat the whole submit as fully successful just because the HTTP call
 * itself returned 200.
 */
export interface AttendanceMarkResultResponse {
  studentId: string;
  success: boolean;
  record: AttendanceRecordResponse | null;
  reason: string | null;
}

/**
 * Shared query params for `GET /api/v1/attendance/my` and `GET
 * /api/v1/attendance/reports`. `from`/`to` must be full ISO-8601 instants
 * (e.g. `2026-01-15T00:00:00.000Z`), not bare `YYYY-MM-DD` dates — the
 * backend binds them as `java.time.Instant`. A day-granularity filter UI
 * must convert the selected start/end day to start-of-day/end-of-day
 * instants before calling these hooks (see `lib/validation/attendance.ts`'s
 * `toAttendanceQueryParams`).
 */
export interface AttendanceListParams {
  courseId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const attendanceKeys = {
  all: ["attendance"] as const,
  roster: (sessionId: string) => [...attendanceKeys.all, "roster", sessionId] as const,
  myAll: () => [...attendanceKeys.all, "my"] as const,
  my: (params?: AttendanceListParams) => [...attendanceKeys.myAll(), params ?? {}] as const,
  reportsAll: () => [...attendanceKeys.all, "reports"] as const,
  reports: (params?: AttendanceListParams) => [...attendanceKeys.reportsAll(), params ?? {}] as const,
};

function buildAttendanceListQuery(params?: AttendanceListParams): string {
  const search = new URLSearchParams();
  if (params?.courseId) search.set("courseId", params.courseId);
  if (params?.from) search.set("from", params.from);
  if (params?.to) search.set("to", params.to);
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  if (params?.sort) search.set("sort", params.sort);
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

/**
 * `GET /api/v1/attendance/sessions/{sessionId}/roster` — Teacher-ownership-
 * or-staff `ATTENDANCE`/`VIEW`. `404` for a cross-tenant or Teacher-not-
 * owning `sessionId`, surfaced via `QueryStateBoundary`'s generic error path
 * (never a distinguishable `403` for this specific case, per the backend's
 * anti-enumeration design — see plan §13).
 */
export function useSessionRoster(sessionId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: attendanceKeys.roster(sessionId),
    queryFn: () =>
      authorizedFetch<AttendanceRosterResponse>(
        "tenant",
        `/v1/attendance/sessions/${sessionId}/roster`
      ),
    enabled: sessionId.length > 0,
  });
}

/**
 * `POST /api/v1/attendance/sessions/{sessionId}/records` — Teacher-
 * ownership-or-staff `ATTENDANCE`/`CREATE_EDIT`. Only include rows the
 * caller actually set in `marks` — a row not sent is left untouched
 * server-side, never overwritten with a null/unset status. On success,
 * invalidates this session's roster plus every `my`/`reports` list query
 * (both families, regardless of their specific filter params) since a mark
 * here can change any of those lists' results.
 */
export function useMarkAttendance(sessionId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: MarkAttendanceRequestBody) =>
      authorizedFetch<AttendanceMarkResultResponse[]>(
        "tenant",
        `/v1/attendance/sessions/${sessionId}/records`,
        { method: "POST", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: attendanceKeys.roster(sessionId) });
      queryClient.invalidateQueries({ queryKey: attendanceKeys.myAll() });
      queryClient.invalidateQueries({ queryKey: attendanceKeys.reportsAll() });
    },
  });
}

/**
 * `GET /api/v1/attendance/my` — `hasRole('STUDENT')`, owner-only, no id
 * param. Default sort `markedAt` DESC (matches the backend's
 * `@PageableDefault`). `placeholderData: keepPreviousData` keeps the prior
 * page's rows on screen (instead of flashing back to the loading state)
 * while a page/filter change is in flight — a page-turn is a refinement of
 * the same list, not a new list. `query.status` stays `"success"` throughout
 * that refetch (it never flips back to `"pending"`); only `isPlaceholderData`
 * (true while the still-displayed data is the *previous* page's) and
 * `isFetching` (true whenever a request for this query is in flight) change.
 * Callers that want a busy indicator during that background refetch must
 * check `isFetching`/`isPlaceholderData` explicitly — see the three list
 * pages that consume this hook (`app/(student)/student/attendance/page.tsx`,
 * `app/(teacher)/teacher/attendance/reports/page.tsx`,
 * `app/(tenant-admin)/tenant-admin/attendance/reports/page.tsx`), which all
 * do.
 */
export function useMyAttendance(params?: AttendanceListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildAttendanceListQuery(params);
  return useQuery({
    queryKey: attendanceKeys.my(params),
    queryFn: () =>
      authorizedFetch<PageResponse<AttendanceRecordResponse>>(
        "tenant",
        `/v1/attendance/my${queryString}`
      ),
    placeholderData: keepPreviousData,
  });
}

/**
 * `GET /api/v1/attendance/reports` — role-dispatched server-side (Teacher ->
 * own courses only; Tenant Admin/Attendance Operator/Read-only Auditor ->
 * tenant-wide). Same param shape/default sort/`keepPreviousData` behavior
 * (and the same `isFetching`/`isPlaceholderData`-for-busy-indicator caveat)
 * as `useMyAttendance` above.
 */
export function useAttendanceReports(params?: AttendanceListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildAttendanceListQuery(params);
  return useQuery({
    queryKey: attendanceKeys.reports(params),
    queryFn: () =>
      authorizedFetch<PageResponse<AttendanceRecordResponse>>(
        "tenant",
        `/v1/attendance/reports${queryString}`
      ),
    placeholderData: keepPreviousData,
  });
}
