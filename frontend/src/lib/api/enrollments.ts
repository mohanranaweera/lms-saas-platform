import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for `enrollment-management`'s MVP-012
 * "Enrollment and Course Access" endpoints (`/api/v1/enrollments/**`,
 * `/api/v1/reactivation-requests/**` — see `EnrollmentController`/
 * `ReactivationRequestController`). Follows `lib/api/payment-slips.ts`'s
 * conventions exactly (full `/api/v1/...` paths, every call through
 * `useAuth().authorizedFetch("tenant", ...)`, query-keys object, `onSuccess`
 * cache invalidation).
 *
 * `GET /api/v1/courses/{courseId}/access-state` (`CourseAccessStateController`)
 * has no client here — nothing in this module's approved frontend scope needs
 * a single-course check (`useMyEnrollments` below already returns every
 * enrollment's access state in one call); wiring another module's per-course
 * screen to it is that module's own future work, not this one's.
 *
 * `GET /api/v1/enrollments/my/courses` (MVP-013 "Student Dashboard") closes
 * the course-name gap MVP-012 left open: `hasRole('STUDENT')`, owner-only, no
 * id/query param, returns one `CourseSummaryResponse` row (`id`, `name`,
 * `slug`, `category`) per course the caller currently has a CURRENT
 * enrollment in (any access state), as a bare array (empty if none). Callers
 * (`student/courses`, `student/dashboard`) resolve a display name/category by
 * matching `EnrollmentSummaryResponse.courseId` against this response's `id`;
 * a course id this endpoint omits (should be structurally rare — see the
 * backend's own doc) falls back to `shortId(courseId)` from `lib/format.ts`
 * rather than the enrollment row disappearing. `ReactivationRequestResponse`
 * still carries no `courseId` at all — only `enrollmentId` — so reactivation
 * queue/history rows still render `shortId(enrollmentId, "Enrollment")`
 * unchanged; this endpoint doesn't help there.
 */

export type EnrollmentAccessStateType = "NEVER_ENROLLED" | "ACTIVE" | "EXPIRED";
export type ReactivationRequestStatus = "SUBMITTED" | "APPROVED" | "REJECTED";

/** Mirrors `EnrollmentSummaryResponse` — one row of `GET /api/v1/enrollments/my`'s response body (a plain array, not a `PageResponse`). */
export interface EnrollmentSummaryResponse {
  enrollmentId: string;
  courseId: string;
  state: EnrollmentAccessStateType;
  accessExpiresAt: string | null;
  canRequestReactivation: boolean;
}

/** Mirrors `CourseSummaryResponse` — one row of `GET /api/v1/enrollments/my/courses`'s response body (a plain array, not a `PageResponse`). */
export interface CourseSummaryResponse {
  id: string;
  name: string;
  slug: string;
  category: string;
}

/**
 * Builds the `courseId -> CourseSummaryResponse` lookup both `student/
 * dashboard` and `student/courses` need to resolve a display name/category
 * for an enrollment row. Shared here (rather than duplicated per page) so
 * there's one place to change the shortId-fallback contract; callers should
 * wrap this in `useMemo` keyed on the query's `data` reference, since it
 * otherwise reallocates a new `Map` on every render.
 */
export function indexCourseSummariesById(
  summaries: CourseSummaryResponse[] | undefined
): Map<string, CourseSummaryResponse> {
  return new Map((summaries ?? []).map((course) => [course.id, course]));
}

/** Mirrors `ReactivationRequestResponse` field-for-field. */
export interface ReactivationRequestResponse {
  id: string;
  enrollmentId: string;
  requestedBy: string;
  status: ReactivationRequestStatus;
  reviewedBy: string | null;
  reviewedAt: string | null;
  newOrderId: string | null;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors `ReactivationApproveRequest` — `note` is optional/nullable. */
export interface ReactivationApproveRequestBody {
  note?: string;
}

/** Mirrors `ReactivationRejectRequest` — `reason` is required, non-blank. */
export interface ReactivationRejectRequestBody {
  reason: string;
}

export interface ReactivationQueueParams {
  /** Omit for the real backend "all statuses" default — unlike the slip queue, there is no fake "pending" sentinel here. */
  status?: ReactivationRequestStatus;
  page?: number;
  size?: number;
  sort?: string;
}

export const enrollmentKeys = {
  all: ["enrollments"] as const,
  my: () => [...enrollmentKeys.all, "my"] as const,
  myCourses: () => [...enrollmentKeys.all, "my", "courses"] as const,
};

export const reactivationRequestKeys = {
  all: ["reactivation-requests"] as const,
  detail: (id: string) => [...reactivationRequestKeys.all, "detail", id] as const,
  myAll: () => [...reactivationRequestKeys.all, "my"] as const,
  my: (params?: { page?: number; size?: number }) =>
    [...reactivationRequestKeys.myAll(), params ?? {}] as const,
  queueAll: () => [...reactivationRequestKeys.all, "queue"] as const,
  queue: (params?: ReactivationQueueParams) =>
    [...reactivationRequestKeys.queueAll(), params ?? {}] as const,
};

/** `GET /api/v1/enrollments/my` — `hasRole('STUDENT')`, owner-only, plain array (no pagination). */
export function useMyEnrollments() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: enrollmentKeys.my(),
    queryFn: () => authorizedFetch<EnrollmentSummaryResponse[]>("tenant", "/api/v1/enrollments/my"),
  });
}

/**
 * `GET /api/v1/enrollments/my/courses` — `hasRole('STUDENT')`, owner-only,
 * plain array (no pagination). One `CourseSummaryResponse` per course the
 * caller currently has a CURRENT enrollment in. Callers must treat a failure
 * of this query as graceful degradation (fall back to `shortId(courseId)`
 * per course row), never as blocking for the enrollment list itself — see
 * `student/courses/page.tsx` and `student/dashboard/page.tsx`.
 */
export function useMyEnrolledCourseSummaries() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: enrollmentKeys.myCourses(),
    queryFn: () =>
      authorizedFetch<CourseSummaryResponse[]>("tenant", "/api/v1/enrollments/my/courses"),
  });
}

function buildReactivationListQuery(params?: {
  status?: ReactivationRequestStatus;
  page?: number;
  size?: number;
  sort?: string;
}): string {
  const search = new URLSearchParams();
  if (params?.status) search.set("status", params.status);
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  if (params?.sort) search.set("sort", params.sort);
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

/**
 * `POST /api/v1/enrollments/{enrollmentId}/reactivation-requests` — owning
 * student only (server-resolved; `404` for an enrollment that isn't the
 * caller's own, anti-enumeration). No request body. `409` if the enrollment
 * isn't currently `EXPIRED` or an open (`SUBMITTED`) request already exists —
 * this hook surfaces that verbatim via `error.message`, it never guesses
 * eligibility itself.
 */
export function useSubmitReactivationRequest(enrollmentId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<ReactivationRequestResponse>(
        "tenant",
        `/api/v1/enrollments/${enrollmentId}/reactivation-requests`,
        { method: "POST" }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: reactivationRequestKeys.myAll() });
      queryClient.invalidateQueries({ queryKey: enrollmentKeys.my() });
    },
  });
}

/** `GET /api/v1/reactivation-requests/my` — owning student only, paginated, default sort `createdAt` DESC. */
export function useMyReactivationRequests(params?: { page?: number; size?: number }) {
  const { authorizedFetch } = useAuth();
  const queryString = buildReactivationListQuery(params);
  return useQuery({
    queryKey: reactivationRequestKeys.my(params),
    queryFn: () =>
      authorizedFetch<PageResponse<ReactivationRequestResponse>>(
        "tenant",
        `/api/v1/reactivation-requests/my${queryString}`
      ),
  });
}

/**
 * `GET /api/v1/reactivation-requests/{id}` — owner student OR staff
 * `ACCESS_EXPIRY`/`VIEW`; `404` anti-enumeration otherwise (never 403).
 */
export function useReactivationRequest(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: reactivationRequestKeys.detail(id),
    queryFn: () =>
      authorizedFetch<ReactivationRequestResponse>("tenant", `/api/v1/reactivation-requests/${id}`),
    enabled: id.length > 0,
  });
}

/**
 * `GET /api/v1/reactivation-requests` — staff `ACCESS_EXPIRY`/`VIEW` queue
 * (403 for a student), default sort `createdAt` ASC (FIFO). Omitting `status`
 * returns every status — a real backend "all" option, unlike the slip
 * review queue's pending-only default, so no fake filter sentinel is needed
 * here.
 */
export function useReactivationQueue(params?: ReactivationQueueParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildReactivationListQuery(params);
  return useQuery({
    queryKey: reactivationRequestKeys.queue(params),
    queryFn: () =>
      authorizedFetch<PageResponse<ReactivationRequestResponse>>(
        "tenant",
        `/api/v1/reactivation-requests${queryString}`
      ),
  });
}

/**
 * `POST /api/v1/reactivation-requests/{id}/approve` — staff
 * `ACCESS_EXPIRY`/`APPROVE` only (Tenant Admin is the only role holding this
 * grant). Idempotent if already `APPROVED`. `note` is optional.
 */
export function useApproveReactivationRequest(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ReactivationApproveRequestBody) =>
      authorizedFetch<ReactivationRequestResponse>(
        "tenant",
        `/api/v1/reactivation-requests/${id}/approve`,
        { method: "POST", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: reactivationRequestKeys.detail(id) });
      queryClient.invalidateQueries({ queryKey: reactivationRequestKeys.queueAll() });
    },
  });
}

/**
 * `POST /api/v1/reactivation-requests/{id}/reject` — same auth as
 * `useApproveReactivationRequest`. `reason` is required, non-blank.
 */
export function useRejectReactivationRequest(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ReactivationRejectRequestBody) =>
      authorizedFetch<ReactivationRequestResponse>(
        "tenant",
        `/api/v1/reactivation-requests/${id}/reject`,
        { method: "POST", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: reactivationRequestKeys.detail(id) });
      queryClient.invalidateQueries({ queryKey: reactivationRequestKeys.queueAll() });
    },
  });
}
