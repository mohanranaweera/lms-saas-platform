import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for `live-class-management`'s Wave 4
 * (PAR-19-01..05) `class-session` endpoints (`/api/v1/class-sessions/**` —
 * see `ClassSessionController`). Follows `lib/api/attendance.ts`/
 * `lib/api/courses.ts`'s exact conventions: every call through
 * `useAuth().authorizedFetch("tenant", ...)`, a query-keys factory object,
 * `onSuccess` cache invalidation on mutations.
 *
 * `join`/`getRecording` are deliberately `useMutation`s, not `useQuery`s,
 * even though `getRecording` is a `GET` on the wire — both mint a fresh,
 * short-lived, single-use URL server-side on every call (never a stable/
 * cacheable resource per `.claude/rules/security.md`'s "Video & Session
 * Protection"), so neither may ever be cached/prefetched/refetched-in-the-
 * background by React Query the way an ordinary query would be. Callers must
 * call `mutate`/`mutateAsync` fresh at the moment of the user's click, never
 * reuse a previously returned `joinUrl`/`playbackUrl`.
 */

export type ClassSessionStatus = "SCHEDULED" | "LIVE" | "COMPLETED" | "CANCELLED";
export type MeetingProvider = "ZOOM";
export type ClassSessionProviderStatus = "PENDING" | "PROVISIONED" | "FAILED";

/** Mirrors `ClassSessionResponse` field-for-field. Never carries a `providerReference` — see that DTO's own doc comment. */
export interface ClassSessionResponse {
  id: string;
  courseId: string;
  teacherId: string;
  lessonId: string | null;
  title: string;
  description: string | null;
  scheduledStart: string;
  scheduledEnd: string;
  status: ClassSessionStatus;
  meetingProvider: MeetingProvider;
  providerStatus: ClassSessionProviderStatus;
  providerFailureReason: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors `ClassSessionCreateRequest` — `POST /v1/class-sessions`'s request body. `teacherId` is never sent; derived server-side. */
export interface ClassSessionCreateRequest {
  courseId: string;
  lessonId?: string;
  title: string;
  description?: string;
  scheduledStart: string;
  scheduledEnd: string;
}

/** Mirrors `ClassSessionUpdateRequest` — `PATCH /v1/class-sessions/{id}`'s request body. Legal only while `status === "SCHEDULED"` (409 otherwise). */
export interface ClassSessionUpdateRequest {
  title: string;
  description?: string;
  scheduledStart: string;
  scheduledEnd: string;
}

/** Mirrors `ClassSessionJoinResponse` — `POST /v1/class-sessions/{id}/join`'s response body. */
export interface ClassSessionJoinResponse {
  joinUrl: string;
  expiresAt: string;
}

/** Mirrors `ClassSessionRecordingResponse` — `GET /v1/class-sessions/{id}/recording`'s response body. */
export interface ClassSessionRecordingResponse {
  playbackUrl: string;
  expiresAt: string;
}

export interface ClassSessionListParams {
  courseId?: string;
  status?: ClassSessionStatus;
  /** Full ISO-8601 instant, not a bare date — the backend binds this as `java.time.Instant`. */
  from?: string;
  to?: string;
}

export const classSessionKeys = {
  all: ["class-sessions"] as const,
  list: (params?: ClassSessionListParams) => [...classSessionKeys.all, "list", params ?? {}] as const,
  detail: (sessionId: string) => [...classSessionKeys.all, "detail", sessionId] as const,
};

function buildClassSessionListQuery(params?: ClassSessionListParams): string {
  const search = new URLSearchParams();
  if (params?.courseId) search.set("courseId", params.courseId);
  if (params?.status) search.set("status", params.status);
  if (params?.from) search.set("from", params.from);
  if (params?.to) search.set("to", params.to);
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

/**
 * `GET /api/v1/class-sessions` — server-side entitlement-filtered by caller
 * role (Teacher: own courses only; staff with `LIVE_CLASSES`/`VIEW`:
 * tenant-wide; Student: only sessions for their own currently-ACTIVE
 * enrollments). `courseId`/`status`/`from`/`to` are additional narrowing
 * filters on top of that server-derived set only — never a way to widen it,
 * and this hook never applies its own client-side identity filtering on top.
 * Plain array (no pagination) — mirrors `ClassSessionController#listSessions`.
 */
export function useClassSessions(params?: ClassSessionListParams, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  const queryString = buildClassSessionListQuery(params);
  return useQuery({
    queryKey: classSessionKeys.list(params),
    queryFn: () =>
      authorizedFetch<ClassSessionResponse[]>("tenant", `/v1/class-sessions${queryString}`),
    enabled: options?.enabled ?? true,
  });
}

/**
 * `GET /api/v1/class-sessions/{id}` — same three-way entitlement gate as the
 * list read. A Student addressing a session they have no entitlement to gets
 * a real backend `404` (anti-enumeration — never a distinguishable `403`),
 * which `QueryStateBoundary` renders as an ordinary error/not-found state.
 */
export function useClassSession(sessionId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: classSessionKeys.detail(sessionId),
    queryFn: () => authorizedFetch<ClassSessionResponse>("tenant", `/v1/class-sessions/${sessionId}`),
    enabled: sessionId.length > 0,
  });
}

/** `POST /api/v1/class-sessions` — Teacher (own course) or staff `LIVE_CLASSES`/`CREATE_EDIT`. */
export function useCreateClassSession() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ClassSessionCreateRequest) =>
      authorizedFetch<ClassSessionResponse>("tenant", "/v1/class-sessions", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: classSessionKeys.all });
    },
  });
}

/** `PATCH /api/v1/class-sessions/{id}` — legal only while `SCHEDULED` (409 otherwise). */
export function useUpdateClassSession(sessionId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ClassSessionUpdateRequest) =>
      authorizedFetch<ClassSessionResponse>("tenant", `/v1/class-sessions/${sessionId}`, {
        method: "PATCH",
        body: JSON.stringify(body),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(classSessionKeys.detail(sessionId), updated);
      queryClient.invalidateQueries({ queryKey: classSessionKeys.all });
    },
  });
}

/** `POST /api/v1/class-sessions/{id}/retry-provisioning` — idempotent no-op if already `PROVISIONED`; retries if `PENDING`/`FAILED`. */
export function useRetryClassSessionProvisioning(sessionId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<ClassSessionResponse>("tenant", `/v1/class-sessions/${sessionId}/retry-provisioning`, {
        method: "POST",
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(classSessionKeys.detail(sessionId), updated);
      queryClient.invalidateQueries({ queryKey: classSessionKeys.all });
    },
  });
}

function useClassSessionTransition(sessionId: string, action: "start" | "complete" | "cancel") {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<ClassSessionResponse>("tenant", `/v1/class-sessions/${sessionId}/${action}`, {
        method: "POST",
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(classSessionKeys.detail(sessionId), updated);
      queryClient.invalidateQueries({ queryKey: classSessionKeys.all });
    },
  });
}

/** `POST /api/v1/class-sessions/{id}/start` — `SCHEDULED -> LIVE`. Illegal source state returns 409. */
export function useStartClassSession(sessionId: string) {
  return useClassSessionTransition(sessionId, "start");
}

/** `POST /api/v1/class-sessions/{id}/complete` — `LIVE -> COMPLETED`. Illegal source state returns 409. */
export function useCompleteClassSession(sessionId: string) {
  return useClassSessionTransition(sessionId, "complete");
}

/** `POST /api/v1/class-sessions/{id}/cancel` — illegal source state returns 409. */
export function useCancelClassSession(sessionId: string) {
  return useClassSessionTransition(sessionId, "cancel");
}

/**
 * `POST /api/v1/class-sessions/{id}/join` — entitlement-checked fresh on
 * every call (Teacher: ownership-only; Student: `ACTIVE` enrollment AND
 * `status === "LIVE"` AND `providerStatus === "PROVISIONED"`). Returns a
 * single-use, short-lived `joinUrl` — the caller must `window.open` it
 * immediately (new tab, `noopener,noreferrer`) and never cache/reuse it. A
 * stale-state join (not yet `LIVE`, not yet `PROVISIONED`) surfaces as a
 * `409` — render `error.message` inline (e.g. "This class session is not
 * currently live"), never a generic toast.
 */
export function useJoinClassSession(sessionId: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<ClassSessionJoinResponse>("tenant", `/v1/class-sessions/${sessionId}/join`, {
        method: "POST",
      }),
  });
}

/**
 * `GET /api/v1/class-sessions/{id}/recording` — same entitlement gate as
 * join, `COMPLETED` sessions only. `useMutation` (not `useQuery`) so it is
 * never cached/refetched in the background — every call mints a fresh
 * short-lived `playbackUrl`; the caller must `window.open` it immediately and
 * never reuse a previously returned value.
 */
export function useClassSessionRecording(sessionId: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<ClassSessionRecordingResponse>("tenant", `/v1/class-sessions/${sessionId}/recording`),
  });
}
