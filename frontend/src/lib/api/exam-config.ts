import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { ApiClientError, isApiClientError } from "./error";

/**
 * Typed client + React Query hooks for the Klass Exam Module "Slice B"
 * (exam configuration, directory, paper) — `docs/api/exam-management-v2.md`.
 * Every call goes through `useAuth().authorizedFetch("tenant", ...)`; the
 * tenant is resolved by the backend from the access token, never sent from
 * here. Exam status is derived server-side from the server clock; this module
 * never computes it from the browser clock.
 *
 * The exam password is write-only here (D6): it is sent on create/update and
 * never read back (`ExamDetailResponse` only carries `hasPassword`).
 */

export type ExamType = "MCQ" | "WRITTEN" | "PHYSICAL";
/** Server-derived from the injected `Clock` (`DRAFT` kept for incomplete legacy rows). */
export type ExamLiveStatus = "DRAFT" | "UPCOMING" | "IN_PROGRESS" | "FINISHED";
export type PaymentCriteriaKind = "MONTHS" | "MODULES" | "DATES" | "INSTALLMENT" | "FULL_FEE";
export type ExamDocumentPurpose = "QUESTION_PAPER" | "MARKING_SCHEME";

export interface PaymentCriteria {
  kind: PaymentCriteriaKind;
  /** `YYYY-MM` (MONTHS). */
  months?: string[] | null;
  /** Course module ids (MODULES). */
  moduleIds?: string[] | null;
  /** `YYYY-MM-DD` (DATES). */
  dates?: string[] | null;
  /** INSTALLMENT. BigDecimal on the wire. */
  minimumAmount?: number | string | null;
}

export interface ModuleQuotaRequest {
  moduleId: string;
  questionCount: number;
}

/** Mirrors `ExamUpsertRequest`. Type-specific fields not used by `type` are sent as `null`. */
export interface ExamUpsertRequest {
  type: ExamType;
  name: string;
  courseId: string;
  password: string | null;
  durationMinutes: number | null;
  startAt: string;
  endAt: string;
  questionCount: number | null;
  cutoffMarks: number | null;
  moduleQuotas: ModuleQuotaRequest[] | null;
  shuffleQuestions: boolean;
  shuffleAnswers: boolean;
  enrollAllStudents: boolean;
  autoEnrollOnPayment: boolean;
  paymentCriteria: PaymentCriteria | null;
  showAnswersWithResults: boolean;
  totalMarks: number | null;
  questionPaperMediaId: string | null;
  markingSchemeMediaId: string | null;
}

export interface ExamModuleQuota {
  moduleId: string;
  moduleName: string;
  questionCount: number;
}

/** Mirrors `ExamDetailResponse` (no `password`, ever). */
export interface ExamDetail {
  id: string;
  type: ExamType;
  status: ExamLiveStatus;
  name: string;
  courseId: string;
  courseName: string;
  hasPassword: boolean;
  durationMinutes: number | null;
  startAt: string;
  endAt: string;
  questionCount: number | null;
  paperQuestionCount: number | null;
  cutoffMarks: number | string | null;
  moduleQuotas: ExamModuleQuota[] | null;
  shuffleQuestions: boolean;
  shuffleAnswers: boolean;
  enrollAllStudents: boolean;
  autoEnrollOnPayment: boolean;
  paymentCriteria: PaymentCriteria | null;
  showAnswersWithResults: boolean;
  totalMarks: number | string | null;
  questionPaperMediaId: string | null;
  markingSchemeMediaId: string | null;
  questionPaper: { fileName: string } | null;
  markingScheme: { fileName: string } | null;
  locked: boolean;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors `ExamCard`. Counts are 0 until Slice C/D tables exist. */
export interface ExamCard {
  id: string;
  type: ExamType;
  status: ExamLiveStatus;
  name: string;
  courseId: string;
  courseName: string;
  startAt: string;
  endAt: string;
  durationMinutes: number | null;
  questionCount: number | null;
  totalMarks: number | string | null;
  enrolled: number;
  attended: number;
  completed: number;
  submitted: number;
}

export interface ExamDirectory {
  serverNow: string;
  inProgress: ExamCard[];
  upcoming: ExamCard[];
  previous: ExamCard[];
}

export interface ExamDocumentUploadResponse {
  mediaId: string;
  fileName: string;
}

export interface ExamDocumentLink {
  url: string;
  fileName: string;
}

/** `GET /exams/course-config/{courseId}` (Slice B addendum). `paymentCriteriaKind: null` = auto-enroll unsupported. */
export interface ExamCourseConfig {
  courseId: string;
  pricingModel: string;
  paymentCriteriaKind: PaymentCriteriaKind | null;
}

export const examConfigKeys = {
  all: ["exam-config"] as const,
  directoryAll: () => [...examConfigKeys.all, "directory"] as const,
  directory: (search: string) => [...examConfigKeys.directoryAll(), { search }] as const,
  detail: (examId: string) => [...examConfigKeys.all, "detail", examId] as const,
  courseConfig: (courseId: string) => [...examConfigKeys.all, "course-config", courseId] as const,
};

const enc = encodeURIComponent;

/** `GET /exams/directory?search=` — grouped by server-derived status, with `serverNow`. */
export function useExamDirectory(search: string) {
  const { authorizedFetch } = useAuth();
  const trimmed = search.trim();
  return useQuery({
    queryKey: examConfigKeys.directory(trimmed),
    queryFn: () =>
      authorizedFetch<ExamDirectory>(
        "tenant",
        `/v1/exams/directory${trimmed ? `?search=${enc(trimmed)}` : ""}`
      ),
    placeholderData: keepPreviousData,
  });
}

/** `GET /exams/{id}` — edit form + header. Never contains the password. */
export function useExamDetail(examId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examConfigKeys.detail(examId),
    queryFn: () => authorizedFetch<ExamDetail>("tenant", `/v1/exams/${enc(examId)}`),
    enabled: examId.length > 0 && (options?.enabled ?? true),
    staleTime: 0,
  });
}

/** `GET /exams/course-config/{courseId}` — decides which payment-criteria editor applies. */
export function useExamCourseConfig(courseId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examConfigKeys.courseConfig(courseId),
    queryFn: () => authorizedFetch<ExamCourseConfig>("tenant", `/v1/exams/course-config/${enc(courseId)}`),
    enabled: courseId.length > 0 && (options?.enabled ?? true),
  });
}

function useInvalidateExams() {
  const queryClient = useQueryClient();
  return (examId?: string) => {
    void queryClient.invalidateQueries({ queryKey: examConfigKeys.directoryAll() });
    if (examId) void queryClient.invalidateQueries({ queryKey: examConfigKeys.detail(examId) });
  };
}

/** `POST /exams` → 201 `ExamDetailResponse`. MCQ papers are drawn and frozen server-side (D3). */
export function useCreateExam() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateExams();
  return useMutation({
    mutationFn: (body: ExamUpsertRequest) =>
      authorizedFetch<ExamDetail>("tenant", "/v1/exams", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (exam) => invalidate(exam.id),
  });
}

/** `PUT /exams/{id}` — 409 `EXAM_LOCKED` when a locked field changes after the first attempt. */
export function useUpdateExam(examId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateExams();
  return useMutation({
    mutationFn: (body: ExamUpsertRequest) =>
      authorizedFetch<ExamDetail>("tenant", `/v1/exams/${enc(examId)}`, { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: () => invalidate(examId),
  });
}

/** `DELETE /exams/{id}` — 204 soft delete; 409 once any attempt/result/submission exists. */
export function useDeleteExam() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateExams();
  return useMutation({
    // 204 has no envelope; `apiFetch` reports it as status 204, which is success here.
    mutationFn: async (examId: string) => {
      try {
        await authorizedFetch<null>("tenant", `/v1/exams/${enc(examId)}`, { method: "DELETE" });
      } catch (error) {
        if (isApiClientError(error) && error.status === 204) return;
        throw error;
      }
    },
    onSuccess: (_data, examId) => invalidate(examId),
  });
}

/** `POST /exams/{id}/paper/redraw` — new random draw from the saved quotas; 409 if locked. */
export function useRedrawExamPaper(examId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  const invalidate = useInvalidateExams();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<ExamDetail>("tenant", `/v1/exams/${enc(examId)}/paper/redraw`, { method: "POST" }),
    onSuccess: (exam) => {
      queryClient.setQueryData(examConfigKeys.detail(examId), exam);
      invalidate();
    },
  });
}

/** `POST /exams/{id}/password/reveal` — audit-logged server-side. Not used by the Slice B forms (D6). */
export function useRevealExamPassword(examId: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<{ password: string }>("tenant", `/v1/exams/${enc(examId)}/password/reveal`, { method: "POST" }),
  });
}

/** `POST /exam-documents?purpose=` (multipart `file`) — PDF/PNG/JPEG ≤ 20 MB, sniffed server-side. */
export function useUploadExamDocument(purpose: ExamDocumentPurpose) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append("file", file);
      return authorizedFetch<ExamDocumentUploadResponse>("tenant", `/v1/exam-documents?purpose=${purpose}`, {
        method: "POST",
        body: form,
      });
    },
  });
}

/** `GET /exams/{id}/documents/{purpose}` — short-lived signed link (staff only). */
export function useExamDocumentLink() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({ examId, purpose }: { examId: string; purpose: ExamDocumentPurpose }) =>
      authorizedFetch<ExamDocumentLink>("tenant", `/v1/exams/${enc(examId)}/documents/${purpose}`),
  });
}

/** 409 `EXAM_LOCKED`: a locked field was changed after the first attempt. */
export function isExamLockedError(error: unknown): boolean {
  return error instanceof ApiClientError && error.status === 409 && error.code === "EXAM_LOCKED";
}
