import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "./error";
import type { PageResponse } from "./courses";
import { examConfigKeys } from "./exam-config";
import { examManagementKeys } from "./exam-management";

/**
 * Typed client + React Query hooks for the Klass Exam Module "Slice E"
 * (`docs/api/exam-management-v2.md` — results publication, Notify Marks,
 * stats, leaderboards).
 *
 * Every fact rendered from these hooks is computed by the server: who gets
 * notified, whether results are published, the stats populations
 * (enrolled / attended / graded), pass counts, question distributions,
 * leaderboard totals, ties and shares. The browser never derives a rank, a
 * total or a pass count itself, and visibility of a leaderboard to students is
 * decided server-side (`published` + the student's own enrollments). The
 * tenant always comes from the access token.
 */

const enc = encodeURIComponent;

function noRetryOn4xx(count: number, error: unknown): boolean {
  return !(isApiClientError(error) && error.status >= 400 && error.status < 500) && count < 2;
}

// ---------------------------------------------------------------------------
// Notify Marks
// ---------------------------------------------------------------------------

/** `GET /exams/{id}/notify-marks/preview` — `recipients` = enrolled students with marks. */
export interface NotifyMarksPreview {
  recipients: number;
  withoutMarks: number;
  alreadyNotifiedAt: string | null;
  resultsPublishedAt?: string | null;
  endAt?: string | null;
  /** Server clock: the exam window has closed. */
  ended?: boolean | null;
}

/** `POST /exams/{id}/notify-marks` → 200. `replayed` = same key as an earlier send (nothing re-sent). */
export interface NotifyMarksResult {
  dispatchId?: string | null;
  recipients?: number | null;
  withoutMarks?: number | null;
  resend?: boolean | null;
  sentAt?: string | null;
  resultsPublishedAt?: string | null;
  replayed?: boolean | null;
}

export function notifiedCount(result: NotifyMarksResult | null | undefined, fallback: number): number {
  const value = result?.recipients;
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

export const examResultsKeys = {
  all: ["exam-results"] as const,
  notifyPreview: (examId: string) => [...examResultsKeys.all, "notify-preview", examId] as const,
  stats: (examId: string) => [...examResultsKeys.all, "stats", examId] as const,
  leaderboardsAll: () => [...examResultsKeys.all, "leaderboards"] as const,
  leaderboards: () => [...examResultsKeys.leaderboardsAll(), "list"] as const,
  leaderboard: (id: string) => [...examResultsKeys.leaderboardsAll(), "detail", id] as const,
  ranks: (id: string, params: RanksParams) => [...examResultsKeys.leaderboardsAll(), "ranks", id, params] as const,
  studentAll: () => [...examResultsKeys.all, "student-leaderboards"] as const,
  studentLeaderboards: () => [...examResultsKeys.studentAll(), "list"] as const,
  studentRanks: (id: string, params: RanksParams) => [...examResultsKeys.studentAll(), "ranks", id, params] as const,
};

/** Preview of who Notify Marks will reach. Always re-read when the dialog opens. */
export function useNotifyMarksPreview(examId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examResultsKeys.notifyPreview(examId),
    queryFn: () => authorizedFetch<NotifyMarksPreview>("tenant", `/v1/exams/${enc(examId)}/notify-marks/preview`),
    enabled: examId.length > 0 && (options?.enabled ?? true),
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: false,
    retry: noRetryOn4xx,
  });
}

/**
 * `POST /exams/{id}/notify-marks` with `Idempotency-Key`. The caller owns the
 * key (one per dialog opening, reused on retry) so a lost response can be
 * retried without notifying anyone twice; the server publishes the results
 * and enqueues the notifications. A deliberate second send needs
 * `resend: true` and a new key.
 */
export function useSendNotifyMarks(examId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ idempotencyKey, resend }: { idempotencyKey: string; resend: boolean }) =>
      authorizedFetch<NotifyMarksResult | null>("tenant", `/v1/exams/${enc(examId)}/notify-marks`, {
        method: "POST",
        headers: { "Idempotency-Key": idempotencyKey },
        body: JSON.stringify(resend ? { resend: true } : {}),
      }),
    // The preview is only refreshed after a success: after a lost response the dialog must keep
    // the audience it showed, so the retry (same key) isn't blocked by our own "already notified".
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: examManagementKeys.all(examId) });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: examResultsKeys.notifyPreview(examId) });
      void queryClient.invalidateQueries({ queryKey: examManagementKeys.all(examId) });
      void queryClient.invalidateQueries({ queryKey: examConfigKeys.detail(examId) });
      void queryClient.invalidateQueries({ queryKey: examResultsKeys.stats(examId) });
      void queryClient.invalidateQueries({ queryKey: examResultsKeys.leaderboardsAll() });
    },
  });
}

/** 409 codes the Notify Marks endpoint answers with. */
export function notifyConflictCode(error: unknown): "NOT_ENDED" | "ALREADY_NOTIFIED" | "NO_RECIPIENTS" | null {
  if (!isApiClientError(error) || error.status !== 409) return null;
  if (error.code === "NOT_ENDED" || error.code === "ALREADY_NOTIFIED" || error.code === "NO_RECIPIENTS") return error.code;
  return null;
}

// ---------------------------------------------------------------------------
// Stats
// ---------------------------------------------------------------------------

export interface ExamStatsOption {
  id: string;
  html: string;
  correct: boolean;
  selectedCount: number;
}

export interface ExamStatsQuestion {
  sequence: number;
  bodyHtml: string;
  marks: number | string | null;
  correct: number;
  incorrect: number;
  unanswered: number;
  correctPercent: number | string | null;
  options: ExamStatsOption[];
}

/**
 * `GET /exams/{id}/stats`. Average / min / max are over graded attended
 * students; `passed` counts graded students at or above the cut-off and
 * `passedOf` is the attended count. Nullable values mean "no population" and
 * are rendered as "—", never as NaN/undefined. `questions` only for MCQ.
 */
export interface ExamStats {
  examId?: string;
  type?: "MCQ" | "WRITTEN" | "PHYSICAL";
  population: { enrolled: number; attended: number; graded: number };
  averageMarks: number | string | null;
  maxMarks: number | string | null;
  minMarks: number | string | null;
  passed: number | null;
  passedOf: number | null;
  cutoffMarks: number | string | null;
  totalMarks: number | string | null;
  questions?: ExamStatsQuestion[] | null;
  frequentlyMissed?: number[] | null;
}

export function useExamStats(examId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examResultsKeys.stats(examId),
    queryFn: () => authorizedFetch<ExamStats>("tenant", `/v1/exams/${enc(examId)}/stats`),
    enabled: examId.length > 0,
    staleTime: 0,
    retry: noRetryOn4xx,
  });
}

// ---------------------------------------------------------------------------
// Leaderboards (D7)
// ---------------------------------------------------------------------------

export interface LeaderboardSummary {
  id: string;
  name: string;
  examCount: number;
  /** Staff list only; the student list contains published leaderboards only. */
  published?: boolean;
}

/** `GET /exam-leaderboards/{id}`. */
export interface LeaderboardDetail {
  id: string;
  name: string;
  published: boolean;
  examIds?: string[] | null;
  exams?: LeaderboardExam[] | null;
  createdAt?: string;
  updatedAt?: string;
}

export function leaderboardExamIds(detail: LeaderboardDetail): string[] {
  if (detail.examIds && detail.examIds.length > 0) return detail.examIds;
  return (detail.exams ?? []).map((exam) => exam.id);
}

export interface LeaderboardUpsert {
  name: string;
  examIds: string[];
  published: boolean;
}

export interface LeaderboardExam {
  id: string;
  name: string;
  totalMarks: number | string | null;
  published: boolean;
}

export interface LeaderboardRankRow {
  rank: number;
  /** Masked (null) for other students on the student view. */
  studentId: string | null;
  indexNumber: string | null;
  name: string;
  totalMarks: number | string | null;
  maxPossible: number | string | null;
  share: number | string | null;
  /** Empty for other students on the student view. */
  perExam: { examId: string; marks: number | string | null }[] | null;
  /** The signed-in student's own row (student view). */
  self?: boolean | null;
}

/** `GET …/ranks` (staff and student share the shape). */
export interface LeaderboardRanks {
  id: string;
  name: string;
  published: boolean;
  exams: LeaderboardExam[];
  rows: LeaderboardRankRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  /** `false` (and no rows) → "Ranks will appear once marks are entered." */
  hasPublishedMarks: boolean;
}

function normalizeRanks(raw: LeaderboardRanks): LeaderboardRanks {
  return {
    ...raw,
    exams: raw.exams ?? [],
    rows: raw.rows ?? [],
    totalElements: raw.totalElements ?? raw.rows?.length ?? 0,
  };
}

function listOf<T>(data: T[] | PageResponse<T> | null | undefined): T[] {
  if (!data) return [];
  if (Array.isArray(data)) return data;
  return data.content ?? [];
}

export interface RanksParams {
  search: string;
  page: number;
  size: number;
}

function ranksQuery(params: RanksParams): string {
  const query = new URLSearchParams();
  const search = params.search.trim();
  if (search) query.set("search", search);
  query.set("page", String(params.page));
  query.set("size", String(params.size));
  return query.toString();
}

/** Staff: `GET /exam-leaderboards`. */
export function useLeaderboards() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examResultsKeys.leaderboards(),
    queryFn: async () =>
      listOf(await authorizedFetch<LeaderboardSummary[] | PageResponse<LeaderboardSummary>>("tenant", "/v1/exam-leaderboards")),
    staleTime: 0,
    retry: noRetryOn4xx,
  });
}

/** Staff: `GET /exam-leaderboards/{id}`. */
export function useLeaderboard(id: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examResultsKeys.leaderboard(id),
    queryFn: () => authorizedFetch<LeaderboardDetail>("tenant", `/v1/exam-leaderboards/${enc(id)}`),
    enabled: id.length > 0 && (options?.enabled ?? true),
    staleTime: 0,
    retry: noRetryOn4xx,
  });
}

/** Staff: `GET /exam-leaderboards/{id}/ranks?search=&page=&size=`. */
export function useLeaderboardRanks(id: string, params: RanksParams) {
  const { authorizedFetch } = useAuth();
  const normalized = { ...params, search: params.search.trim() };
  return useQuery({
    queryKey: examResultsKeys.ranks(id, normalized),
    queryFn: async () =>
      normalizeRanks(await authorizedFetch<LeaderboardRanks>("tenant", `/v1/exam-leaderboards/${enc(id)}/ranks?${ranksQuery(normalized)}`)),
    enabled: id.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 0,
    retry: noRetryOn4xx,
  });
}

function useInvalidateLeaderboards() {
  const queryClient = useQueryClient();
  return () => void queryClient.invalidateQueries({ queryKey: examResultsKeys.leaderboardsAll() });
}

/** `POST /exam-leaderboards` — `{ name, examIds[≥2], published }` (validated server-side). */
export function useCreateLeaderboard() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateLeaderboards();
  return useMutation({
    mutationFn: (body: LeaderboardUpsert) =>
      authorizedFetch<LeaderboardDetail>("tenant", "/v1/exam-leaderboards", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: invalidate,
  });
}

/** `PUT /exam-leaderboards/{id}`. */
export function useUpdateLeaderboard(id: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateLeaderboards();
  return useMutation({
    mutationFn: (body: LeaderboardUpsert) =>
      authorizedFetch<LeaderboardDetail>("tenant", `/v1/exam-leaderboards/${enc(id)}`, { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: invalidate,
  });
}

/** `DELETE /exam-leaderboards/{id}` — removes the grouping only; exams and marks are untouched. */
export function useDeleteLeaderboard() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateLeaderboards();
  return useMutation({
    mutationFn: async (id: string) => {
      try {
        await authorizedFetch<null>("tenant", `/v1/exam-leaderboards/${enc(id)}`, { method: "DELETE" });
      } catch (error) {
        // 204 has no envelope; `apiFetch` reports it as status 204, which is success here.
        if (isApiClientError(error) && error.status === 204) return;
        throw error;
      }
    },
    onSuccess: invalidate,
  });
}

/** Student: `GET /student/leaderboards` — published leaderboards that include one of the student's exams. */
export function useStudentLeaderboards() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examResultsKeys.studentLeaderboards(),
    queryFn: async () =>
      listOf(await authorizedFetch<LeaderboardSummary[] | PageResponse<LeaderboardSummary>>("tenant", "/v1/student/leaderboards")),
    staleTime: 0,
    retry: noRetryOn4xx,
  });
}

/** Student: `GET /student/leaderboards/{id}/ranks?search=&page=&size=`. */
export function useStudentLeaderboardRanks(id: string, params: RanksParams) {
  const { authorizedFetch } = useAuth();
  const normalized = { ...params, search: params.search.trim() };
  return useQuery({
    queryKey: examResultsKeys.studentRanks(id, normalized),
    queryFn: async () =>
      normalizeRanks(await authorizedFetch<LeaderboardRanks>("tenant", `/v1/student/leaderboards/${enc(id)}/ranks?${ranksQuery(normalized)}`)),
    enabled: id.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 0,
    retry: noRetryOn4xx,
  });
}

/** Finite number from a server number/decimal-string, or `null`. Never NaN. */
export function finiteOrNull(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === "") return null;
  const n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}
