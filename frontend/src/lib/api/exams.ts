import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for `exam-management`'s MVP-017 "Exams"
 * endpoints (`/api/v1/exams/**` — see `QuestionBankController`/
 * `ExamController`/`ExamAttemptController`/`MarkingQueueController`/
 * `ResultsController`). Follows `lib/api/attendance.ts`'s conventions
 * exactly: every call through `useAuth().authorizedFetch("tenant", ...)`, a
 * query-keys factory object, `keepPreviousData` on paginated list queries,
 * `onSuccess` cache invalidation on mutations.
 *
 * **Gap closed (post-review):** `GET /courses/{courseId}/exams` (course-scoped,
 * every status) and `GET /exams` (tenant-wide, staff-only, optional `status`
 * filter) were added to `ExamController`/`ExamSchedulingService` specifically
 * to fix this — the Teacher Marking Queue, Teacher Results Publishing, and
 * Tenant Admin Exam Oversight screens now have a real browsing surface
 * instead of the earlier ID-paste-only workaround. `GET /exams/attempts/my`
 * (Student's own attempt history) was added the same way, replacing the
 * `lib/exam-attempt-storage.ts` `localStorage` workaround as the primary path
 * for rediscovering a student's own attempt.
 */

export type QuestionType = "MCQ" | "STRUCTURED";
export type ExamStatus = "DRAFT" | "SCHEDULED" | "PUBLISHED" | "CLOSED";
/** `EXPIRED` is reserved/never actually written by the backend today — handled defensively, never assumed absent. */
export type ExamAttemptStatus = "IN_PROGRESS" | "SUBMITTED" | "EXPIRED";

/** Mirrors `ExamQuestionOptionResponse` — `isCorrect` is never present in any response a client can read. */
export interface ExamQuestionOptionResponse {
  id: string;
  optionText: string;
}

/** Mirrors `ExamQuestionResponse` field-for-field. */
export interface ExamQuestionResponse {
  id: string;
  courseId: string;
  questionType: QuestionType;
  body: string;
  options: ExamQuestionOptionResponse[];
}

/** Mirrors `ExamQuestionOptionRequest`. */
export interface ExamQuestionOptionRequest {
  optionText: string;
  isCorrect: boolean;
}

/** Mirrors `ExamQuestionCreateRequest` — `options` is only meaningful for `MCQ`; omit/empty for `STRUCTURED`. */
export interface ExamQuestionCreateRequest {
  questionType: QuestionType;
  body: string;
  options: ExamQuestionOptionRequest[] | null;
}

/** Mirrors `ExamQuestionUpdateRequest` — `questionType` is NOT editable (deliberately absent from this type). */
export interface ExamQuestionUpdateRequest {
  body: string;
  options: ExamQuestionOptionRequest[] | null;
}

/** Mirrors `ExamCreateRequest`. */
export interface ExamCreateRequest {
  title: string;
}

/** Mirrors `ExamUpdateRequest` — `questionIds` REPLACES the entire ordered link set (order = array order). Only legal while `status === "DRAFT"` (409 otherwise). */
export interface ExamUpdateRequest {
  title: string;
  scheduledStart: string;
  scheduledEnd: string;
  timeLimitMinutes: number;
  questionIds: string[];
}

/** Mirrors `ExamResponse` field-for-field. */
export interface ExamResponse {
  id: string;
  courseId: string;
  title: string;
  scheduledStart: string;
  scheduledEnd: string;
  timeLimitMinutes: number;
  status: ExamStatus;
  resultsPublishedAt: string | null;
  questions: ExamQuestionResponse[];
}

/** Mirrors `ExamSummaryResponse` — `GET /my/upcoming`'s row shape (no questions). */
export interface ExamSummaryResponse {
  id: string;
  courseId: string;
  title: string;
  status: ExamStatus;
  scheduledStart: string;
  scheduledEnd: string;
}

/** Mirrors `ExamAttemptResponse` field-for-field. */
export interface ExamAttemptResponse {
  id: string;
  examId: string;
  studentId: string;
  startedAt: string;
  submittedAt: string | null;
  status: ExamAttemptStatus;
}

/** Mirrors `SaveAnswerRequest` — no `examId` field; never send one. */
export interface SaveAnswerRequest {
  questionId: string;
  response: string | null;
}

/** Mirrors `SavedAnswerResponse` — one row per question this attempt has ever saved a response for. */
export interface SavedAnswerResponse {
  questionId: string;
  response: string | null;
}

/** Mirrors `MarkingQueueEntryResponse` field-for-field. */
export interface MarkingQueueEntryResponse {
  answerId: string;
  examId: string;
  attemptId: string;
  questionId: string;
  questionBody: string;
  response: string | null;
}

/** Mirrors `MarkAnswerRequest` — `manualScore` must be non-negative. */
export interface MarkAnswerRequest {
  manualScore: number;
}

/** Mirrors `ExamPublishResultResponse`. */
export interface ExamPublishResultResponse {
  examId: string;
  resultsPublishedAt: string;
}

/**
 * Mirrors `QuestionAnswerResultResponse`. `autoScore`/`manualScore` are
 * backend `BigDecimal` values — they may arrive as a JSON number or a
 * numeric string depending on serialization; every consumer must wrap with
 * `Number(...)` before formatting/comparing, never assume `typeof === "number"`.
 */
export interface QuestionAnswerResultResponse {
  questionId: string;
  response: string | null;
  autoScore: number | string | null;
  manualScore: number | string | null;
}

/** Mirrors `ExamAttemptResultResponse`. `score`/`maxScore` share the same `BigDecimal`-serialization caveat as above. */
export interface ExamAttemptResultResponse {
  attemptId: string;
  examId: string;
  status: ExamAttemptStatus;
  score: number | string;
  maxScore: number | string;
  answers: QuestionAnswerResultResponse[];
}

/** Mirrors `ExamResultsResponse` — `result` is `null` whenever `published === false` (the "submitted, awaiting publish" state). */
export interface ExamResultsResponse {
  published: boolean;
  result: ExamAttemptResultResponse | null;
}

export interface ExamListParams {
  page?: number;
  size?: number;
  sort?: string;
}

function buildListQuery(params?: ExamListParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  if (params?.sort) search.set("sort", params.sort);
  return `?${search.toString()}`;
}

export const examKeys = {
  all: ["exams"] as const,
  questionsAll: (courseId: string) => [...examKeys.all, "questions", courseId] as const,
  questions: (courseId: string, params?: ExamListParams) =>
    [...examKeys.questionsAll(courseId), params ?? {}] as const,
  detailAll: () => [...examKeys.all, "detail"] as const,
  detail: (examId: string) => [...examKeys.detailAll(), examId] as const,
  myUpcomingAll: () => [...examKeys.all, "my-upcoming"] as const,
  myUpcoming: (params?: ExamListParams) => [...examKeys.myUpcomingAll(), params ?? {}] as const,
  courseExamsAll: (courseId: string) => [...examKeys.all, "course-exams", courseId] as const,
  courseExams: (courseId: string, params?: ExamListParams) =>
    [...examKeys.courseExamsAll(courseId), params ?? {}] as const,
  tenantExamsAll: () => [...examKeys.all, "tenant-exams"] as const,
  tenantExams: (status: ExamStatus | undefined, params?: ExamListParams) =>
    [...examKeys.tenantExamsAll(), status ?? null, params ?? {}] as const,
  myAttemptsAll: () => [...examKeys.all, "my-attempts"] as const,
  myAttempts: (params?: ExamListParams) => [...examKeys.myAttemptsAll(), params ?? {}] as const,
  markingQueueAll: (examId: string) => [...examKeys.all, "marking-queue", examId] as const,
  markingQueue: (examId: string, params?: ExamListParams) =>
    [...examKeys.markingQueueAll(examId), params ?? {}] as const,
  resultsAll: () => [...examKeys.all, "results"] as const,
  results: (attemptId: string) => [...examKeys.resultsAll(), attemptId] as const,
  attemptAnswersAll: () => [...examKeys.all, "attempt-answers"] as const,
  attemptAnswers: (attemptId: string) => [...examKeys.attemptAnswersAll(), attemptId] as const,
};

// ---------------------------------------------------------------------------
// Question bank
// ---------------------------------------------------------------------------

/** `GET /api/v1/exams/courses/{courseId}/questions` — owning Teacher, TA (tenant-wide), or `DomainArea.EXAMS`/VIEW staff. 404 cross-tenant course, 403 non-owning Teacher. */
export function useCourseQuestions(courseId: string, params?: ExamListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildListQuery(params);
  return useQuery({
    queryKey: examKeys.questions(courseId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<ExamQuestionResponse>>(
        "tenant",
        `/v1/exams/courses/${courseId}/questions${queryString}`
      ),
    enabled: courseId.length > 0,
    placeholderData: keepPreviousData,
  });
}

/** `POST /api/v1/exams/courses/{courseId}/questions` — `requireAuthoringAccess`. */
export function useCreateQuestion(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ExamQuestionCreateRequest) =>
      authorizedFetch<ExamQuestionResponse>("tenant", `/v1/exams/courses/${courseId}/questions`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: examKeys.questionsAll(courseId) });
    },
  });
}

/** `PUT /api/v1/exams/questions/{questionId}` — same auth as create. Can 409 `QUESTION_IN_USE` (see `isQuestionInUseError`). */
export function useUpdateQuestion(courseId: string, questionId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ExamQuestionUpdateRequest) =>
      authorizedFetch<ExamQuestionResponse>("tenant", `/v1/exams/questions/${questionId}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: examKeys.questionsAll(courseId) });
    },
  });
}

/** `DELETE /api/v1/exams/questions/{questionId}` — 409 `CONFLICT` if referenced by an exam/answer. */
export function useDeleteQuestion(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (questionId: string) =>
      authorizedFetch<null>("tenant", `/v1/exams/questions/${questionId}`, { method: "DELETE" }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: examKeys.questionsAll(courseId) });
    },
  });
}

// ---------------------------------------------------------------------------
// Exam scheduling
// ---------------------------------------------------------------------------

/** `POST /api/v1/exams/courses/{courseId}/exams` — `requireAuthoringAccess`. Starts as `DRAFT`. */
export function useCreateDraftExam(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (body: ExamCreateRequest) =>
      authorizedFetch<ExamResponse>("tenant", `/v1/exams/courses/${courseId}/exams`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
  });
}

/** `GET /api/v1/exams/{examId}` — full exam incl. questions/options (never `isCorrect`). */
export function useExam(examId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examKeys.detail(examId),
    queryFn: () => authorizedFetch<ExamResponse>("tenant", `/v1/exams/${examId}`),
    enabled: examId.length > 0,
  });
}

/** `PUT /api/v1/exams/{examId}` — only legal while `status === "DRAFT"` (409 otherwise). */
export function useUpdateDraftExam(examId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ExamUpdateRequest) =>
      authorizedFetch<ExamResponse>("tenant", `/v1/exams/${examId}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: (updated) => {
      queryClient.setQueryData(examKeys.detail(examId), updated);
    },
  });
}

/** `POST /api/v1/exams/{examId}/schedule` — the single `DRAFT -> SCHEDULED` transition. 403 for Teacher Assistant unconditionally; 400 `VALIDATION_ERROR` if zero linked questions or invalid window. */
export function useScheduleExam(examId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<ExamResponse>("tenant", `/v1/exams/${examId}/schedule`, { method: "POST" }),
    onSuccess: (updated) => {
      queryClient.setQueryData(examKeys.detail(examId), updated);
    },
  });
}

/** `GET /api/v1/exams/my/upcoming` — Student only, owner-only. Per-course enrollment intersected with `status IN (SCHEDULED, PUBLISHED)` — never returns a `CLOSED` exam. */
export function useMyUpcomingExams(params?: ExamListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildListQuery(params);
  return useQuery({
    queryKey: examKeys.myUpcoming(params),
    queryFn: () =>
      authorizedFetch<PageResponse<ExamSummaryResponse>>("tenant", `/v1/exams/my/upcoming${queryString}`),
    placeholderData: keepPreviousData,
  });
}

/**
 * `GET /api/v1/exams/courses/{courseId}/exams` — every status, not just
 * upcoming ones. Auth: owning Teacher, tenant-wide Teacher Assistant, or
 * `DomainArea.EXAMS`/VIEW staff. Backs the Teacher's own-course exam list
 * (Marking Queue / Results Publishing exam pickers).
 */
export function useCourseExams(courseId: string, params?: ExamListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildListQuery(params);
  return useQuery({
    queryKey: examKeys.courseExams(courseId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<ExamSummaryResponse>>(
        "tenant",
        `/v1/exams/courses/${courseId}/exams${queryString}`
      ),
    enabled: courseId.length > 0,
    placeholderData: keepPreviousData,
  });
}

/**
 * `GET /api/v1/exams` — tenant-wide, staff-only (Tenant Admin/Exam Manager;
 * Read-only Auditor via VIEW). Backs the Tenant Admin Exam Oversight screen.
 * `status` is an optional filter.
 */
export function useTenantExams(status?: ExamStatus, params?: ExamListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildListQuery(params);
  const statusParam = status ? `&status=${status}` : "";
  return useQuery({
    queryKey: examKeys.tenantExams(status, params),
    queryFn: () =>
      authorizedFetch<PageResponse<ExamSummaryResponse>>("tenant", `/v1/exams${queryString}${statusParam}`),
    placeholderData: keepPreviousData,
  });
}

/**
 * `GET /api/v1/exams/attempts/my` — Student only, owner-only. The calling
 * student's own attempt history (most recent first), including attempts for
 * `CLOSED` exams that `useMyUpcomingExams` never returns. Replaces
 * `lib/exam-attempt-storage.ts`'s `localStorage` workaround as the primary
 * way to rediscover a past attempt from any device/session.
 */
export function useMyAttempts(params?: ExamListParams, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  const queryString = buildListQuery(params);
  return useQuery({
    queryKey: examKeys.myAttempts(params),
    queryFn: () => authorizedFetch<PageResponse<ExamAttemptResponse>>("tenant", `/v1/exams/attempts/my${queryString}`),
    enabled: options?.enabled ?? true,
    placeholderData: keepPreviousData,
  });
}

// ---------------------------------------------------------------------------
// Exam taking (Student)
// ---------------------------------------------------------------------------

/**
 * `POST /api/v1/exams/{examId}/attempts` — starts or resumes the caller's
 * single `IN_PROGRESS` attempt. Can reject 403 with `error.code`
 * `NOT_YET_OPEN`/`WINDOW_CLOSED` — callers must branch on `error.code`, never
 * compare timestamps themselves.
 *
 * **Deliberately a `useQuery`, not a `useMutation`, despite the `POST`
 * verb.** The backend's own contract ("starts OR RESUMES the single
 * `IN_PROGRESS` attempt", backed by a partial-unique-index guard) makes this
 * operation idempotent from the caller's perspective — the same shape
 * TanStack Query's own guidance uses to distinguish "fetch-like" operations
 * (`useQuery`, safe to fire automatically on mount) from genuinely
 * non-idempotent user-triggered actions (`useMutation`, which the library
 * explicitly recommends never firing from a mount effect). A `useMutation`
 * fired from this page's mount effect was confirmed, via a live debug
 * session against this exact page, to resolve its network call correctly
 * but never notify the component under React's default `reactStrictMode:
 * true` in `next dev` (a StrictMode/mutation-observer interaction specific
 * to firing `mutate()` synchronously on mount) — `useQuery`'s automatic-fetch
 * path is the one TanStack Query hardens for exactly this timing, so this
 * hook is built on it instead. `retry: false` since a `NOT_YET_OPEN`/
 * `WINDOW_CLOSED`/409-concurrent-attempt rejection must surface immediately,
 * not auto-retry; `refetchOnWindowFocus`/`refetchOnMount` disabled since
 * re-fetching this on an unrelated focus/mount event would be a surprising,
 * possibly-window-violating side effect for a page whose whole point is one
 * deliberate "start" action per visit — the caller uses `refetch()` for an
 * explicit user-initiated retry after a failure.
 */
export function useStartAttempt(examId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: [...examKeys.detail(examId), "attempt"],
    queryFn: () =>
      authorizedFetch<ExamAttemptResponse>("tenant", `/v1/exams/${examId}/attempts`, { method: "POST" }),
    enabled: examId.length > 0,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnMount: false,
    staleTime: Infinity,
  });
}

/** `PUT /api/v1/exams/attempts/{attemptId}/answers` — 409 if the attempt is already `SUBMITTED`. */
export function useSaveAnswer(attemptId: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (body: SaveAnswerRequest) =>
      authorizedFetch<null>("tenant", `/v1/exams/attempts/${attemptId}/answers`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
  });
}

/**
 * `GET /api/v1/exams/attempts/{attemptId}/answers` — Student only, owner-only
 * (404 for a cross-student attempt id, same pattern as every other endpoint
 * in `ExamAttemptController`). Backs resuming an `IN_PROGRESS` attempt after
 * a refresh/crash (take-page Fix 1): seeds the page's local `answers` state
 * so a previously saved answer never renders blank, and a subsequent "Save
 * answer" click never silently overwrites a good saved answer with `null`.
 */
export function useAttemptAnswers(attemptId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examKeys.attemptAnswers(attemptId),
    queryFn: () => authorizedFetch<SavedAnswerResponse[]>("tenant", `/v1/exams/attempts/${attemptId}/answers`),
    enabled: (options?.enabled ?? true) && attemptId.length > 0,
  });
}

/**
 * `POST /api/v1/exams/attempts/{attemptId}/submit` — 409 on a second submit;
 * treat as "already submitted", not a fatal error. Can also 403
 * `WINDOW_CLOSED` (confirmed directly against
 * `ExamAttemptService.submit`/`requireAttemptableWindow`, which re-verifies
 * the exam window on every call, including submit) — callers should branch
 * on `error.code` for that case rather than showing the generic failure
 * message.
 */
export function useSubmitAttempt(attemptId: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<ExamAttemptResponse>("tenant", `/v1/exams/attempts/${attemptId}/submit`, {
        method: "POST",
      }),
  });
}

// ---------------------------------------------------------------------------
// Marking queue
// ---------------------------------------------------------------------------

/** `GET /api/v1/exams/{examId}/marking-queue` — owning Teacher or `DomainArea.EXAMS`/VIEW staff. */
export function useMarkingQueueEntries(examId: string, params?: ExamListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildListQuery(params);
  return useQuery({
    queryKey: examKeys.markingQueue(examId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<MarkingQueueEntryResponse>>(
        "tenant",
        `/v1/exams/${examId}/marking-queue${queryString}`
      ),
    enabled: examId.length > 0,
    placeholderData: keepPreviousData,
  });
}

/** `POST /api/v1/exams/answers/{answerId}/mark` — owning Teacher or `DomainArea.EXAMS`/CREATE_EDIT staff; 403 non-owning Teacher. */
export function useMarkAnswer(examId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ answerId, body }: { answerId: string; body: MarkAnswerRequest }) =>
      authorizedFetch<null>("tenant", `/v1/exams/answers/${answerId}/mark`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: examKeys.markingQueueAll(examId) });
    },
  });
}

// ---------------------------------------------------------------------------
// Results publishing / review
// ---------------------------------------------------------------------------

/** `POST /api/v1/exams/{examId}/publish-results` — `requireLifecycleTransitionAccess`. 409 if `status !== "CLOSED"`. */
export function usePublishResults(examId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<ExamPublishResultResponse>("tenant", `/v1/exams/${examId}/publish-results`, {
        method: "POST",
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: examKeys.detail(examId) });
    },
  });
}

/**
 * `GET /api/v1/exams/attempts/{attemptId}/results` — Student only, owner-only
 * (backend returns 404, not 403, for another student's attempt id — this
 * flows through as a normal not-found case, never a custom permission-denied
 * branch).
 */
export function useExamResults(attemptId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examKeys.results(attemptId),
    queryFn: () =>
      authorizedFetch<ExamResultsResponse>("tenant", `/v1/exams/attempts/${attemptId}/results`),
    enabled: attemptId.length > 0,
  });
}
