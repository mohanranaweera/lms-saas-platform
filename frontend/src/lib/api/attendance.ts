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
 * Wave 8: attendance is taken against a `ClassSession`
 * (`ClassSession -> AttendanceSheet -> AttendanceRecord`) via
 * `useClassSessionRoster`/`useMarkClassSessionAttendance`. The MVP-016
 * lesson-scoped hooks (`useSessionRoster`/`useMarkAttendance`) remain only
 * for the deprecated `/attendance/sessions/{lessonId}/...` endpoints and are
 * no longer used by any screen. On a record, `sessionId` is the LEGACY
 * lesson id (null for class-session records) — render a record's session via
 * `formatAttendanceSession` (`components/attendance/attendance-session-label.ts`),
 * never `shortId(record.sessionId)`.
 */

export type AttendanceStatus = "PRESENT" | "ABSENT" | "LATE";

/** Mirrors `AttendanceSheetSource` — which kind of sheet a record belongs to (Wave 8). */
export type AttendanceSheetSource = "CLASS_SESSION" | "LEGACY_LESSON";

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

/**
 * Mirrors `AttendanceRecordResponse` field-for-field. `sessionId` is the
 * LEGACY lesson id — `null` for a class-session record; `classSessionId`/
 * `classSessionTitle` are `null` for a legacy record.
 */
export interface AttendanceRecordResponse {
  id: string;
  courseId: string;
  sessionId: string | null;
  studentId: string;
  status: AttendanceStatus;
  markedBy: string;
  markedAt: string;
  createdAt: string;
  updatedAt: string;
  sheetId: string;
  source: AttendanceSheetSource | null;
  classSessionId: string | null;
  classSessionTitle: string | null;
}

/** Mirrors `ClassSessionRosterResponse.Entry` (Wave 8). `currentlyEnrolled: false` rows are read-only history. */
export interface ClassSessionRosterEntry {
  studentId: string;
  studentName: string | null;
  status: AttendanceStatus | null;
  currentlyEnrolled: boolean;
}

/**
 * Mirrors `ClassSessionRosterResponse` — `GET
 * /v1/attendance/class-sessions/{classSessionId}/roster`. `markingOpen`
 * mirrors the backend lifecycle gate for display only; the mark endpoint
 * re-enforces it (409) regardless of what the UI shows.
 */
export interface ClassSessionRosterResponse {
  sheetId: string | null;
  classSessionId: string;
  courseId: string;
  title: string;
  scheduledStart: string;
  scheduledEnd: string;
  sessionStatus: "SCHEDULED" | "LIVE" | "COMPLETED" | "CANCELLED";
  markingOpen: boolean;
  markingClosedReason: string | null;
  roster: ClassSessionRosterEntry[];
}

/**
 * Mirrors `AttendanceSummaryRowResponse` — one row of `GET
 * /v1/attendance/summary` (per student) or `GET /v1/attendance/my/summary`
 * (per course). `attendanceRate` is a server-computed 0–100 percentage of
 * `(present + late) / total` — display it, never recompute it.
 */
export interface AttendanceSummaryRow {
  studentId: string;
  studentName: string | null;
  courseId: string;
  courseName: string | null;
  present: number;
  late: number;
  absent: number;
  total: number;
  attendanceRate: number;
}

export interface AttendanceSummaryParams {
  from?: string;
  to?: string;
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
  /** Wave 8 — narrows to one class session's sheet. */
  classSessionId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const attendanceKeys = {
  all: ["attendance"] as const,
  roster: (sessionId: string) => [...attendanceKeys.all, "roster", sessionId] as const,
  classSessionRoster: (classSessionId: string) =>
    [...attendanceKeys.all, "class-session-roster", classSessionId] as const,
  summaryAll: () => [...attendanceKeys.all, "summary"] as const,
  courseSummary: (courseId: string, params?: AttendanceSummaryParams) =>
    [...attendanceKeys.summaryAll(), "course", courseId, params ?? {}] as const,
  mySummary: (params?: AttendanceSummaryParams) =>
    [...attendanceKeys.summaryAll(), "my", params ?? {}] as const,
  myAll: () => [...attendanceKeys.all, "my"] as const,
  my: (params?: AttendanceListParams) => [...attendanceKeys.myAll(), params ?? {}] as const,
  reportsAll: () => [...attendanceKeys.all, "reports"] as const,
  reports: (params?: AttendanceListParams) => [...attendanceKeys.reportsAll(), params ?? {}] as const,
  studentReportAll: (studentId: string) =>
    [...attendanceKeys.all, "student-report", studentId] as const,
  studentReport: (studentId: string, params?: AttendanceListParams) =>
    [...attendanceKeys.studentReportAll(studentId), params ?? {}] as const,
};

function buildAttendanceListQuery(params?: AttendanceListParams): string {
  const search = new URLSearchParams();
  if (params?.courseId) search.set("courseId", params.courseId);
  if (params?.classSessionId) search.set("classSessionId", params.classSessionId);
  if (params?.from) search.set("from", params.from);
  if (params?.to) search.set("to", params.to);
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  if (params?.sort) search.set("sort", params.sort);
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

function buildSummaryQuery(params?: AttendanceSummaryParams, courseId?: string): string {
  const search = new URLSearchParams();
  if (courseId) search.set("courseId", courseId);
  if (params?.from) search.set("from", params.from);
  if (params?.to) search.set("to", params.to);
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

/**
 * `GET /v1/attendance/class-sessions/{classSessionId}/roster` (Wave 8) —
 * Teacher of the session's course or staff `ATTENDANCE`/`VIEW`. A Student
 * gets `403`, a cross-tenant id `404`; both surface through
 * `QueryStateBoundary`. Never creates a sheet server-side.
 */
export function useClassSessionRoster(classSessionId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: attendanceKeys.classSessionRoster(classSessionId),
    queryFn: () =>
      authorizedFetch<ClassSessionRosterResponse>(
        "tenant",
        `/v1/attendance/class-sessions/${classSessionId}/roster`
      ),
    enabled: classSessionId.length > 0,
  });
}

/**
 * `POST /v1/attendance/class-sessions/{classSessionId}/records` (Wave 8) —
 * same batch-partial contract as `useMarkAttendance` (inspect every row's
 * `success`), plus a whole-request `409` when the session is cancelled or has
 * not started yet. Only send rows the user actually changed.
 */
export function useMarkClassSessionAttendance(classSessionId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: MarkAttendanceRequestBody) =>
      authorizedFetch<AttendanceMarkResultResponse[]>(
        "tenant",
        `/v1/attendance/class-sessions/${classSessionId}/records`,
        { method: "POST", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: attendanceKeys.classSessionRoster(classSessionId) });
      queryClient.invalidateQueries({ queryKey: attendanceKeys.myAll() });
      queryClient.invalidateQueries({ queryKey: attendanceKeys.reportsAll() });
      queryClient.invalidateQueries({ queryKey: attendanceKeys.summaryAll() });
    },
  });
}

/**
 * `GET /v1/attendance/summary?courseId=` (Wave 8) — per-student counts and
 * rate for one course; Teacher of that course or staff `ATTENDANCE`/`VIEW`.
 */
export function useCourseAttendanceSummary(
  courseId: string,
  params?: AttendanceSummaryParams,
  options?: { enabled?: boolean }
) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: attendanceKeys.courseSummary(courseId, params),
    queryFn: () =>
      authorizedFetch<AttendanceSummaryRow[]>(
        "tenant",
        `/v1/attendance/summary${buildSummaryQuery(params, courseId)}`
      ),
    enabled: courseId.length > 0 && (options?.enabled ?? true),
  });
}

/** `GET /v1/attendance/my/summary` (Wave 8) — the calling Student's own per-course counts and rate. */
export function useMyAttendanceSummary(params?: AttendanceSummaryParams) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: attendanceKeys.mySummary(params),
    queryFn: () =>
      authorizedFetch<AttendanceSummaryRow[]>(
        "tenant",
        `/v1/attendance/my/summary${buildSummaryQuery(params)}`
      ),
  });
}

/**
 * @deprecated Wave 8 — lesson-scoped (legacy) roster; use `useClassSessionRoster`.
 *
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
 * @deprecated Wave 8 — lesson-scoped (legacy) marking; use `useMarkClassSessionAttendance`.
 *
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
export function useAttendanceReports(
  params?: AttendanceListParams,
  options?: { enabled?: boolean }
) {
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
    enabled: options?.enabled ?? true,
  });
}

/**
 * `GET /v1/attendance/students/{id}/report` (Wave 3, staff-facing,
 * studentId-scoped — extends the existing `AttendanceReportFilter` with an
 * optional `studentId` server-side; this hook calls the dedicated
 * studentId-in-path variant instead). Same param shape/default sort/
 * `keepPreviousData` behavior as `useAttendanceReports`.
 */
export function useStudentAttendanceReport(studentId: string, params?: AttendanceListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildAttendanceListQuery(params);
  return useQuery({
    queryKey: attendanceKeys.studentReport(studentId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<AttendanceRecordResponse>>(
        "tenant",
        `/v1/attendance/students/${studentId}/report${queryString}`
      ),
    enabled: studentId.length > 0,
    placeholderData: keepPreviousData,
  });
}
