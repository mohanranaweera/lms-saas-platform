import { useCallback } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "./error";
import type { ExamLiveStatus, ExamType } from "./exam-config";

/**
 * Typed client + React Query hooks for the Klass Exam Module "Slice C"
 * student participation endpoints (`docs/api/exam-management-v2.md`).
 *
 * The backend is the sole authority for everything here: who is enrolled,
 * whether the exam is open, the attempt deadline (`deadlineAt =
 * min(startedAt + duration, endAt)`), finalization at the deadline, grading,
 * and result release. The browser only displays those facts; it never
 * computes a score, never sees a correct answer before the server releases
 * it, and never extends a deadline. The tenant comes from the access token.
 */

export type AttemptStatus = "IN_PROGRESS" | "SUBMITTED" | "EXPIRED";

/** Mirrors `StudentExamResponse`. `courseId` is not in the Slice C contract; read when the server sends it. */
export interface StudentExam {
  id: string;
  type: ExamType;
  name: string;
  courseId?: string | null;
  courseName: string;
  startAt: string;
  endAt: string;
  durationMinutes: number | null;
  questionCount: number | null;
  totalMarks: number | string | null;
  status: ExamLiveStatus;
  requiresPassword: boolean;
  attempt: { id: string; status: AttemptStatus; deadlineAt: string } | null;
  resultAvailable: boolean;
}

export interface StudentExamList {
  serverNow: string;
  upcoming: StudentExam[];
  inProgress: StudentExam[];
  previous: StudentExam[];
}

export interface AttemptOption {
  id: string;
  html: string;
}

/** One question of the attempt, in the persisted per-attempt order. Never contains `correct` or an explanation. */
export interface AttemptItem {
  id: string;
  sequence: number;
  bodyHtml: string;
  marks: number | string;
  options: AttemptOption[];
  selectedOptionId: string | null;
  revision: number;
}

/** Mirrors `AttemptResponse`. */
export interface Attempt {
  id: string;
  examId: string;
  status: AttemptStatus;
  startedAt: string;
  deadlineAt: string;
  serverNow: string;
  items: AttemptItem[];
}

/** `PUT /student/attempts/{id}/items/{itemId}` response. `serverNow` is optional (used to resync the clock when sent). */
export interface SaveItemResponse {
  saved: boolean;
  stale: boolean;
  revision: number;
  serverNow?: string;
}

export interface ResultOption {
  id: string;
  html: string;
}

export interface ResultItem {
  sequence: number;
  bodyHtml: string;
  marks: number | string;
  awardedMarks: number | string;
  selectedOptionId: string | null;
  correctOptionId: string | null;
  explanationHtml: string | null;
  options: ResultOption[];
}

/** Mirrors `ResultResponse`; `items` only when answers are released. */
export interface ExamResult {
  examId: string;
  attemptId: string;
  score: number | string;
  totalMarks: number | string;
  cutoffMarks: number | string | null;
  passed: boolean | null;
  answeredCount: number;
  items?: ResultItem[] | null;
}

export const studentExamKeys = {
  all: ["student-exams"] as const,
  list: () => [...studentExamKeys.all, "list"] as const,
  detail: (examId: string) => [...studentExamKeys.all, "detail", examId] as const,
  attempt: (attemptId: string) => [...studentExamKeys.all, "attempt", attemptId] as const,
  result: (attemptId: string) => [...studentExamKeys.all, "result", attemptId] as const,
};

const enc = encodeURIComponent;

/** `GET /student/exams` — only exams the student is exam-enrolled in, grouped by the server. */
export function useStudentExams() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentExamKeys.list(),
    queryFn: () => authorizedFetch<StudentExamList>("tenant", "/v1/student/exams"),
    staleTime: 0,
  });
}

/** `GET /student/exams/{id}` — 404 when the student isn't enrolled. */
export function useStudentExam(examId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentExamKeys.detail(examId),
    queryFn: () => authorizedFetch<StudentExam>("tenant", `/v1/student/exams/${enc(examId)}`),
    enabled: examId.length > 0,
    staleTime: 0,
    retry: (count, error) => !(isApiClientError(error) && error.status >= 400 && error.status < 500) && count < 2,
  });
}

/**
 * `GET /student/attempts/{id}` — resume after a refresh. Not refetched on
 * focus: once loaded, the page owns the local answer state and a background
 * refetch must never overwrite a selection that is still being saved (the
 * page seeds its local state once, from a settled fetch).
 */
export function useAttempt(attemptId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentExamKeys.attempt(attemptId),
    queryFn: () => authorizedFetch<Attempt>("tenant", `/v1/student/attempts/${enc(attemptId)}`),
    enabled: attemptId.length > 0 && (options?.enabled ?? true),
    staleTime: Infinity,
    // Always re-read on (re)mount so a resumed attempt reflects the server, not a cached copy.
    refetchOnMount: "always",
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    retry: (count, error) => !(isApiClientError(error) && error.status >= 400 && error.status < 500) && count < 2,
  });
}

/**
 * `POST /student/exams/{id}/attempts` `{ password }` — starts the single
 * attempt, or resumes the IN_PROGRESS one. The password is checked
 * server-side (403 `WRONG_PASSWORD`, rate-limited); 409 `NOT_OPEN` / `CLOSED`
 * / `ALREADY_ATTEMPTED`.
 */
export function useStartAttempt(examId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (password: string | null) =>
      authorizedFetch<Attempt>("tenant", `/v1/student/exams/${enc(examId)}/attempts`, {
        method: "POST",
        body: JSON.stringify({ password }),
      }),
    onSuccess: (attempt) => {
      queryClient.setQueryData(studentExamKeys.attempt(attempt.id), attempt);
      queryClient.setQueryData<StudentExam>(studentExamKeys.detail(examId), (current) =>
        current
          ? { ...current, attempt: { id: attempt.id, status: attempt.status, deadlineAt: attempt.deadlineAt } }
          : current
      );
      void queryClient.invalidateQueries({ queryKey: studentExamKeys.list() });
    },
  });
}

/**
 * Imperative `PUT /student/attempts/{id}/items/{itemId}` used by the autosave
 * queue (retries and ordering are handled by the caller, so this is a plain
 * function rather than a mutation hook).
 */
export function useSaveAttemptItem(attemptId: string) {
  const { authorizedFetch } = useAuth();
  return useCallback(
    (itemId: string, optionId: string | null, revision: number) =>
      authorizedFetch<SaveItemResponse>(
        "tenant",
        `/v1/student/attempts/${enc(attemptId)}/items/${enc(itemId)}`,
        { method: "PUT", body: JSON.stringify({ optionId, revision }) }
      ),
    [authorizedFetch, attemptId]
  );
}

/** `POST /student/attempts/{id}/submit` — idempotent. */
export function useSubmitAttempt(attemptId: string, examId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<Attempt>("tenant", `/v1/student/attempts/${enc(attemptId)}/submit`, { method: "POST" }),
    onSettled: () => {
      // Stale-mark only; the result page refetches on mount (an in-place refetch would re-route the take page).
      void queryClient.invalidateQueries({ queryKey: studentExamKeys.detail(examId), refetchType: "none" });
      void queryClient.invalidateQueries({ queryKey: studentExamKeys.list(), refetchType: "none" });
    },
  });
}

/** `GET /student/attempts/{id}/result` — 409 `NOT_RELEASED` before the window closes. */
export function useAttemptResult(attemptId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentExamKeys.result(attemptId),
    queryFn: () => authorizedFetch<ExamResult>("tenant", `/v1/student/attempts/${enc(attemptId)}/result`),
    enabled: attemptId.length > 0 && (options?.enabled ?? true),
    staleTime: 0,
    retry: (count, error) => !(isApiClientError(error) && error.status >= 400 && error.status < 500) && count < 2,
  });
}

/** 409 with one of the attempt-is-closed codes on save. */
export function isAttemptClosedError(error: unknown): boolean {
  return (
    isApiClientError(error) &&
    error.status === 409 &&
    (error.code === "FINALIZED" || error.code === "DEADLINE_PASSED")
  );
}

/** Transient failures worth retrying: no connection, timeout, rate limit or a server error. */
export function isTransientSaveError(error: unknown): boolean {
  if (!isApiClientError(error)) return true;
  return error.status === 0 || error.status === 408 || error.status === 429 || error.status >= 500;
}

export function isResultNotReleasedError(error: unknown): boolean {
  return isApiClientError(error) && error.status === 409 && error.code === "NOT_RELEASED";
}

/** Student-facing copy for start-attempt failures. */
export function startAttemptErrorMessage(error: unknown): { kind: "password" | "state" | "other"; message: string } {
  if (isApiClientError(error)) {
    if (error.status === 429 || error.code === "RATE_LIMITED" || error.code === "TOO_MANY_REQUESTS") {
      return {
        kind: "password",
        message: "Too many incorrect passwords. Wait a minute, then try again.",
      };
    }
    if (error.code === "WRONG_PASSWORD") {
      return { kind: "password", message: "That password is incorrect. Check it with your teacher and try again." };
    }
    if (error.code === "NOT_OPEN") return { kind: "state", message: "This exam hasn't opened yet." };
    if (error.code === "CLOSED") return { kind: "state", message: "This exam has closed. It can no longer be started." };
    if (error.code === "ALREADY_ATTEMPTED") {
      return { kind: "state", message: "You have already attempted this exam. Only one attempt is allowed." };
    }
    if (error.status === 404) return { kind: "state", message: "You are not enrolled in this exam." };
    if (error.status === 403) return { kind: "other", message: "You don't have permission to start this exam." };
    if (error.status === 0) return { kind: "other", message: error.message };
    return { kind: "other", message: error.message || "Could not start the exam. Please try again." };
  }
  return { kind: "other", message: "Could not start the exam. Please try again." };
}
