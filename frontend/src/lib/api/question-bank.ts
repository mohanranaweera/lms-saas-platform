import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { env } from "@/lib/env";
import { ApiClientError, isApiClientError } from "./error";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for the Klass Exam Module "Slice A"
 * question bank (`docs/api/exam-management-v2.md`). The bank is per module:
 * `/v1/courses/{courseId}/modules/{moduleId}/questions`. Every call goes
 * through `useAuth().authorizedFetch("tenant", ...)`; the tenant is always
 * resolved by the backend from the access token, never sent from here.
 *
 * The MVP-017 course-level bank (`lib/api/exams.ts`) is left untouched.
 *
 * Rich content (`bodyHtml`, option `html`, `explanationHtml`) is sanitized
 * server-side. Images carry only `data-media-id` on write; on read the server
 * adds a short-lived signed `src`.
 */

export interface BankQuestionOption {
  id: string;
  sequence: number;
  html: string;
  correct: boolean;
}

/** Mirrors `QuestionResponse`. `marks` is a BigDecimal: number or numeric string. */
export interface BankQuestion {
  id: string;
  courseId: string;
  moduleId: string;
  sequence: number;
  bodyHtml: string;
  marks: number | string;
  explanationHtml: string | null;
  options: BankQuestionOption[];
  importId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface BankQuestionOptionRequest {
  id: string | null;
  html: string;
  correct: boolean;
}

/** Mirrors `QuestionUpsertRequest`. */
export interface QuestionUpsertRequest {
  bodyHtml: string;
  marks: number;
  explanationHtml: string | null;
  options: BankQuestionOptionRequest[];
}

export interface CopyQuestionsRequest {
  targetCourseId: string;
  targetModuleId: string;
}

export interface CopyQuestionsResponse {
  copied: number;
}

export interface ModuleQuestionCount {
  moduleId: string;
  moduleName: string;
  questionCount: number;
  totalMarks: number | string;
}

export interface ExamMediaUploadResponse {
  mediaId: string;
  url: string;
}

/** Mirrors `QuestionImportResponse`. */
export interface QuestionImport {
  id: string;
  fileName: string;
  importedAt: string;
  questionCount: number;
  importedByName: string | null;
}

/** One rejected CSV row from a 422 import response. */
export interface ImportRowError {
  row: number;
  column: string | null;
  message: string;
}

export interface QuestionImportListParams {
  search?: string;
  page?: number;
  size?: number;
}

export const questionBankKeys = {
  all: ["question-bank"] as const,
  moduleQuestionsAll: (courseId: string, moduleId: string) =>
    [...questionBankKeys.all, "module-questions", courseId, moduleId] as const,
  moduleQuestions: (courseId: string, moduleId: string, page: number, size: number) =>
    [...questionBankKeys.moduleQuestionsAll(courseId, moduleId), { page, size }] as const,
  question: (questionId: string) => [...questionBankKeys.all, "question", questionId] as const,
  counts: (courseId: string) => [...questionBankKeys.all, "counts", courseId] as const,
  importsAll: (courseId: string, moduleId: string) =>
    [...questionBankKeys.all, "imports", courseId, moduleId] as const,
  imports: (courseId: string, moduleId: string, params: QuestionImportListParams) =>
    [...questionBankKeys.importsAll(courseId, moduleId), params] as const,
};

function modulePath(courseId: string, moduleId: string): string {
  return `/v1/courses/${encodeURIComponent(courseId)}/modules/${encodeURIComponent(moduleId)}`;
}

/** BigDecimal-safe marks value. */
export function marksNumber(marks: number | string): number {
  const value = Number(marks);
  return Number.isFinite(value) ? value : 0;
}

export function formatMarks(marks: number | string): string {
  const value = marksNumber(marks);
  const text = Number.isInteger(value) ? String(value) : String(Number(value.toFixed(2)));
  return `${text} ${value === 1 ? "mark" : "marks"}`;
}

/** `GET …/modules/{moduleId}/questions` — ordered by `sequence`, deleted excluded. */
export function useModuleQuestions(courseId: string, moduleId: string, page = 0, size = 50) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: questionBankKeys.moduleQuestions(courseId, moduleId, page, size),
    queryFn: () =>
      authorizedFetch<PageResponse<BankQuestion>>(
        "tenant",
        `${modulePath(courseId, moduleId)}/questions?page=${page}&size=${size}`
      ),
    enabled: courseId.length > 0 && moduleId.length > 0,
    placeholderData: keepPreviousData,
  });
}

/** `GET /exam-questions/{questionId}`. */
export function useBankQuestion(questionId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: questionBankKeys.question(questionId),
    queryFn: () =>
      authorizedFetch<BankQuestion>("tenant", `/v1/exam-questions/${encodeURIComponent(questionId)}`),
    enabled: questionId.length > 0,
    // Signed image URLs expire after 30 minutes; never edit from a stale copy.
    staleTime: 0,
    gcTime: 0,
  });
}

function useInvalidateModule(courseId: string, moduleId: string) {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: questionBankKeys.moduleQuestionsAll(courseId, moduleId) });
    void queryClient.invalidateQueries({ queryKey: questionBankKeys.counts(courseId) });
  };
}

export function useCreateBankQuestion(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateModule(courseId, moduleId);
  return useMutation({
    mutationFn: (body: QuestionUpsertRequest) =>
      authorizedFetch<BankQuestion>("tenant", `${modulePath(courseId, moduleId)}/questions`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: invalidate,
  });
}

export function useUpdateBankQuestion(courseId: string, moduleId: string, questionId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  const invalidate = useInvalidateModule(courseId, moduleId);
  return useMutation({
    mutationFn: (body: QuestionUpsertRequest) =>
      authorizedFetch<BankQuestion>("tenant", `/v1/exam-questions/${encodeURIComponent(questionId)}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      invalidate();
      void queryClient.invalidateQueries({ queryKey: questionBankKeys.question(questionId) });
    },
  });
}

export function useDeleteBankQuestion(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateModule(courseId, moduleId);
  return useMutation({
    // `204 No Content` has no envelope; `apiFetch` reports it as an error with
    // status 204, which is the success case here (same as `discounts.ts`).
    mutationFn: async (questionId: string) => {
      try {
        await authorizedFetch<null>("tenant", `/v1/exam-questions/${encodeURIComponent(questionId)}`, {
          method: "DELETE",
        });
      } catch (error) {
        if (isApiClientError(error) && error.status === 204) return;
        throw error;
      }
    },
    onSuccess: invalidate,
  });
}

/** Copies ALL non-deleted questions of the source module to the end of the target module. */
export function useCopyModuleQuestions(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CopyQuestionsRequest) =>
      authorizedFetch<CopyQuestionsResponse>("tenant", `${modulePath(courseId, moduleId)}/questions/copy`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: (_data, body) => {
      void queryClient.invalidateQueries({
        queryKey: questionBankKeys.moduleQuestionsAll(body.targetCourseId, body.targetModuleId),
      });
      void queryClient.invalidateQueries({ queryKey: questionBankKeys.counts(body.targetCourseId) });
    },
  });
}

/** `GET /courses/{courseId}/modules/question-counts`. */
export function useModuleQuestionCounts(courseId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: questionBankKeys.counts(courseId),
    queryFn: () =>
      authorizedFetch<ModuleQuestionCount[]>(
        "tenant",
        `/v1/courses/${encodeURIComponent(courseId)}/modules/question-counts`
      ),
    enabled: courseId.length > 0 && (options?.enabled ?? true),
  });
}

/** `POST /exam-media` (multipart `file`) — PNG/JPEG/GIF/WebP ≤ 5 MB; type is sniffed server-side. */
export function useUploadExamMedia() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData();
      form.append("file", file);
      return authorizedFetch<ExamMediaUploadResponse>("tenant", "/v1/exam-media", {
        method: "POST",
        body: form,
      });
    },
  });
}

export function useQuestionImports(courseId: string, moduleId: string, params: QuestionImportListParams) {
  const { authorizedFetch } = useAuth();
  const search = new URLSearchParams();
  if (params.search?.trim()) search.set("search", params.search.trim());
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 10));
  return useQuery({
    queryKey: questionBankKeys.imports(courseId, moduleId, params),
    queryFn: () =>
      authorizedFetch<PageResponse<QuestionImport>>(
        "tenant",
        `${modulePath(courseId, moduleId)}/question-imports?${search.toString()}`
      ),
    enabled: courseId.length > 0 && moduleId.length > 0,
    placeholderData: keepPreviousData,
  });
}

/**
 * `POST …/question-imports` — multipart `file` + `Idempotency-Key`. The
 * caller keeps one key per selected file so a retry of the same file never
 * imports twice. A 422 is rejected atomically; read the row errors with
 * {@link importRowErrors}.
 */
export function useImportQuestions(courseId: string, moduleId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateModule(courseId, moduleId);
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ file, idempotencyKey }: { file: File; idempotencyKey: string }) => {
      const form = new FormData();
      form.append("file", file);
      return authorizedFetch<QuestionImport>("tenant", `${modulePath(courseId, moduleId)}/question-imports`, {
        method: "POST",
        body: form,
        headers: { "Idempotency-Key": idempotencyKey },
      });
    },
    onSuccess: () => {
      invalidate();
      void queryClient.invalidateQueries({ queryKey: questionBankKeys.importsAll(courseId, moduleId) });
    },
  });
}

/**
 * Extracts the per-row errors of a 422 import rejection. The API doc gives
 * the body as `{ errors: [...] }`; inside the shared `ApiResponse` envelope
 * that arrives as `error.details.errors`. Field errors are accepted as a
 * fallback (`field` = "row 3: answer 2" style is not assumed).
 */
export function importRowErrors(error: unknown): ImportRowError[] {
  if (!(error instanceof ApiClientError) || error.status !== 422) return [];
  const raw = error.details?.errors;
  if (Array.isArray(raw)) {
    return raw
      .filter((item): item is Record<string, unknown> => typeof item === "object" && item !== null)
      .map((item) => ({
        row: Number(item.row) || 0,
        column: typeof item.column === "string" && item.column ? item.column : null,
        message: typeof item.message === "string" ? item.message : "Invalid value",
      }));
  }
  return error.fieldErrors.map((fieldError) => ({ row: 0, column: fieldError.field || null, message: fieldError.message }));
}

/**
 * Downloads `GET /question-imports/template.csv` (plain CSV, not the JSON
 * envelope, so it bypasses `apiFetch`) with the caller's bearer token.
 */
export function useDownloadQuestionTemplate() {
  const { ensureAccessToken } = useAuth();
  return useMutation({
    mutationFn: async () => {
      const token = await ensureAccessToken("tenant");
      let response: Response;
      try {
        response = await fetch(`${env.NEXT_PUBLIC_API_BASE_URL}/v1/question-imports/template.csv`, {
          credentials: "include",
          headers: { Authorization: `Bearer ${token}` },
        });
      } catch {
        throw new ApiClientError(
          { code: "NETWORK_ERROR", message: "Unable to reach the server. Check your connection and try again.", fieldErrors: [] },
          0
        );
      }
      if (!response.ok) {
        throw new ApiClientError(
          { code: "DOWNLOAD_FAILED", message: "The sample template could not be downloaded.", fieldErrors: [] },
          response.status
        );
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "questions-template.csv";
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    },
  });
}
