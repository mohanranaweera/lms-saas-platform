import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { ApiClientError, isApiClientError } from "./error";
import { examConfigKeys } from "./exam-config";

/**
 * Staff-side exam enrollment (Klass Exam Module "Slice C",
 * `docs/api/exam-management-v2.md`). Exam enrollment is separate from course
 * enrollment (ADR-028): only course participants can be added, and the server
 * re-checks that and the caller's authoring access on every call. "Sync
 * payments" asks the server to run its ADR-032 matcher over payments that are
 * already confirmed — the browser never decides who has paid.
 */

/** A course participant not yet enrolled in the exam. Shape per the contract's "(index number, first/last name)". */
export interface EnrollmentCandidate {
  studentId: string;
  indexNumber: string | null;
  firstName: string;
  lastName: string;
}

export interface AddEnrollmentsResponse {
  added: number;
  alreadyEnrolled: number;
}

export interface SyncPaymentsResponse {
  added: number;
}

export const examEnrollmentKeys = {
  all: ["exam-enrollment"] as const,
  candidatesAll: (examId: string) => [...examEnrollmentKeys.all, "candidates", examId] as const,
  candidates: (examId: string, search: string) => [...examEnrollmentKeys.candidatesAll(examId), { search }] as const,
  /** Prefix Slice D's management table should live under so enrollment writes refresh it. */
  management: (examId: string) => [...examEnrollmentKeys.all, "management", examId] as const,
};

const enc = encodeURIComponent;

/** Accepts either a plain array or a `PageResponse` (`content`), in case the server pages this list. */
function candidateList(data: EnrollmentCandidate[] | { content: EnrollmentCandidate[] } | null | undefined) {
  if (!data) return [];
  return Array.isArray(data) ? data : (data.content ?? []);
}

/** `GET /exams/{id}/enrollment-candidates?search=` — course participants not yet enrolled. */
export function useEnrollmentCandidates(examId: string, search: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  const trimmed = search.trim();
  return useQuery({
    queryKey: examEnrollmentKeys.candidates(examId, trimmed),
    queryFn: async () =>
      candidateList(
        await authorizedFetch<EnrollmentCandidate[] | { content: EnrollmentCandidate[] }>(
          "tenant",
          `/v1/exams/${enc(examId)}/enrollment-candidates${trimmed ? `?search=${enc(trimmed)}` : ""}`
        )
      ),
    enabled: examId.length > 0 && (options?.enabled ?? true),
    placeholderData: keepPreviousData,
    staleTime: 0,
  });
}

function useInvalidateEnrollment(examId: string) {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: examEnrollmentKeys.candidatesAll(examId) });
    void queryClient.invalidateQueries({ queryKey: examEnrollmentKeys.management(examId) });
    void queryClient.invalidateQueries({ queryKey: examConfigKeys.directoryAll() });
  };
}

/** `POST /exams/{id}/enrollments` `{ studentIds }` → `{ added, alreadyEnrolled }`. */
export function useAddExamEnrollments(examId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateEnrollment(examId);
  return useMutation({
    mutationFn: (studentIds: string[]) =>
      authorizedFetch<AddEnrollmentsResponse>("tenant", `/v1/exams/${enc(examId)}/enrollments`, {
        method: "POST",
        body: JSON.stringify({ studentIds }),
      }),
    onSuccess: invalidate,
  });
}

/** `DELETE /exams/{id}/enrollments/{studentId}` — 204; 409 `HAS_ATTEMPT` once the student has an attempt/result/submission. */
export function useRemoveExamEnrollment(examId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateEnrollment(examId);
  return useMutation({
    mutationFn: async (studentId: string) => {
      try {
        await authorizedFetch<null>("tenant", `/v1/exams/${enc(examId)}/enrollments/${enc(studentId)}`, {
          method: "DELETE",
        });
      } catch (error) {
        // 204 has no envelope; `apiFetch` reports it as status 204, which is success here.
        if (isApiClientError(error) && error.status === 204) return;
        throw error;
      }
    },
    onSuccess: invalidate,
  });
}

/** `POST /exams/{id}/enrollments/sync-payments` → `{ added }`. */
export function useSyncExamPaymentEnrollments(examId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateEnrollment(examId);
  return useMutation({
    mutationFn: () =>
      authorizedFetch<SyncPaymentsResponse>("tenant", `/v1/exams/${enc(examId)}/enrollments/sync-payments`, {
        method: "POST",
      }),
    onSuccess: invalidate,
  });
}

export const HAS_ATTEMPT_MESSAGE =
  "This student has already started or submitted this exam, so they can't be removed from it.";

export function isHasAttemptError(error: unknown): boolean {
  return error instanceof ApiClientError && error.status === 409 && error.code === "HAS_ATTEMPT";
}

export function candidateName(candidate: Pick<EnrollmentCandidate, "firstName" | "lastName">): string {
  return [candidate.firstName, candidate.lastName].filter(Boolean).join(" ").trim() || "Unnamed student";
}
