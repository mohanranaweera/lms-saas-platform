import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { env } from "@/lib/env";
import { ApiClientError, isApiClientError } from "./error";
import type { PageResponse } from "./courses";
import { examConfigKeys, type ExamDetail, type ExamDocumentLink } from "./exam-config";
import { examEnrollmentKeys } from "./exam-enrollment";
import { studentExamKeys } from "./student-exams";

/**
 * Typed client + React Query hooks for the Klass Exam Module "Slice D"
 * (management, written & physical marking — `docs/api/exam-management-v2.md`).
 *
 * The server is the authority for every fact shown here: participation
 * (attended / completed / submitted / graded / published), the per-student
 * `ResultLabel`, marks bounds, the reason-on-change rule, upload validation
 * (type sniffing, size, count), and who may do what. The browser only
 * displays those facts and forwards staff input; every write is re-authorized
 * and audit-logged server-side. The tenant comes from the access token.
 */

export type ResultLabel =
  | "NOT_PARTICIPATED"
  | "PENDING"
  | "IN_PROGRESS"
  | "AWAITING_MARKS"
  | "PASSED"
  | "FAILED"
  | "MARKED"
  | "ABSENT";

export type Attendance = "PRESENT" | "ABSENT";

export interface ExamDocumentRef {
  id: string;
  fileName: string;
}

/** Mirrors `ManagedStudentRow`. Index numbers are strings (leading zeros preserved). */
export interface ManagedStudentRow {
  studentId: string;
  indexNumber: string | null;
  firstName: string;
  lastName: string;
  result: ResultLabel;
  marks: number | string | null;
  attemptStatus: "IN_PROGRESS" | "SUBMITTED" | "EXPIRED" | null;
  answeredCount: number | null;
  answerDocuments: ExamDocumentRef[] | null;
  markedPaper: { fileName: string } | null;
  attendance: Attendance | null;
  enrollmentSource: string | null;
  /** Not in the Slice D contract: links the index number to the student profile when the server sends it. */
  studentProfileId?: string | null;
}

export interface ExamManagementSummary {
  enrolled: number;
  attended: number;
  attendedPercent: number | string | null;
  completed: number;
  completedPercent: number | string | null;
  submitted: number;
  submittedPercent: number | string | null;
}

/** `GET /exams/{id}/management` — `exam` carries no password (D6). */
export interface ExamManagement {
  serverNow?: string | null;
  /** Set once Notify Marks published the results (Slice E). */
  resultsPublishedAt?: string | null;
  exam: ExamDetail;
  summary: ExamManagementSummary;
  students: PageResponse<ManagedStudentRow>;
}

export interface ExamManagementParams {
  search: string;
  page: number;
  size: number;
}

export interface SaveResultRequest {
  marks: number | null;
  attendance: Attendance | null;
  reason: string | null;
}

export const examManagementKeys = {
  /** Lives under the Slice C prefix so enrollment writes refresh the table too. */
  all: (examId: string) => examEnrollmentKeys.management(examId),
  list: (examId: string, params: ExamManagementParams) => [...examManagementKeys.all(examId), params] as const,
};

const enc = encodeURIComponent;

function managementQuery(params: ExamManagementParams): string {
  const query = new URLSearchParams();
  const search = params.search.trim();
  if (search) query.set("search", search);
  query.set("page", String(params.page));
  query.set("size", String(params.size));
  return query.toString();
}

/** `GET /exams/{id}/management?search=&page=&size=` — header facts, summary counts and one page of enrolled students. */
export function useExamManagement(examId: string, params: ExamManagementParams) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: examManagementKeys.list(examId, { ...params, search: params.search.trim() }),
    queryFn: () =>
      authorizedFetch<ExamManagement>("tenant", `/v1/exams/${enc(examId)}/management?${managementQuery(params)}`),
    enabled: examId.length > 0,
    placeholderData: keepPreviousData,
    staleTime: 0,
    retry: (count, error) => !(isApiClientError(error) && error.status >= 400 && error.status < 500) && count < 2,
  });
}

function useInvalidateManagement(examId: string) {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: examManagementKeys.all(examId) });
    void queryClient.invalidateQueries({ queryKey: examConfigKeys.detail(examId) });
    void queryClient.invalidateQueries({ queryKey: examConfigKeys.directoryAll() });
  };
}

function networkError(): ApiClientError {
  return new ApiClientError(
    { code: "NETWORK_ERROR", message: "Unable to reach the server. Check your connection and try again.", fieldErrors: [] },
    0
  );
}

function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;
  const star = /filename\*=(?:UTF-8'')?([^;]+)/i.exec(header);
  if (star) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ""));
    } catch {
      /* fall through */
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header);
  return plain ? plain[1].trim() : null;
}

/** Safe default file name from the exam name (letters, digits, dashes). */
export function csvFileName(examName: string): string {
  const slug = examName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
  return `${slug || "exam"}-students.csv`;
}

/**
 * `GET /exams/{id}/export.csv?search=` — the server builds the CSV (full
 * filtered set, formula-escaped, index numbers as text). It is plain CSV, not
 * the JSON envelope, so it is fetched with the bearer token and saved as a blob.
 */
export function useExportExamCsv(examId: string) {
  const { ensureAccessToken } = useAuth();
  return useMutation({
    mutationFn: async ({ search, fallbackName }: { search: string; fallbackName: string }) => {
      const query = search.trim() ? `?search=${enc(search.trim())}` : "";
      const url = `${env.NEXT_PUBLIC_API_BASE_URL}/v1/exams/${enc(examId)}/export.csv${query}`;
      const run = async (force: boolean) => {
        const token = await ensureAccessToken("tenant", force ? { force: true } : undefined);
        try {
          return await fetch(url, { credentials: "include", headers: { Authorization: `Bearer ${token}` } });
        } catch {
          throw networkError();
        }
      };
      let response = await run(false);
      if (response.status === 401) response = await run(true);
      if (!response.ok) {
        let message = "The CSV export could not be downloaded.";
        let code = "DOWNLOAD_FAILED";
        try {
          const payload = (await response.json()) as { error?: { code?: string; message?: string } };
          if (payload?.error?.message) message = payload.error.message;
          if (payload?.error?.code) code = payload.error.code;
        } catch {
          /* not JSON */
        }
        throw new ApiClientError({ code, message, fieldErrors: [] }, response.status);
      }
      const blob = await response.blob();
      const fileName = fileNameFromDisposition(response.headers.get("Content-Disposition")) ?? fallbackName;
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
      return { fileName };
    },
  });
}

/**
 * `PUT /exams/{id}/results/{studentId}` — WRITTEN/PHYSICAL only. The server
 * enforces `0 ≤ marks ≤ totalMarks`, requires `reason` when existing marks
 * change, and audit-logs before/after.
 */
export function useSaveExamResult(examId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateManagement(examId);
  return useMutation({
    mutationFn: ({ studentId, body }: { studentId: string; body: SaveResultRequest }) =>
      authorizedFetch<unknown>("tenant", `/v1/exams/${enc(examId)}/results/${enc(studentId)}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: invalidate,
  });
}

/** `PUT /exams/{id}/total-marks` — PHYSICAL only (D8); 409 when existing marks exceed the new total. */
export function useSetExamTotalMarks(examId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateManagement(examId);
  return useMutation({
    mutationFn: (totalMarks: number) =>
      authorizedFetch<unknown>("tenant", `/v1/exams/${enc(examId)}/total-marks`, {
        method: "PUT",
        body: JSON.stringify({ totalMarks }),
      }),
    onSuccess: invalidate,
  });
}

/** Unwraps a JSON `ApiResponse<T>` body read by XHR; throws `ApiClientError` like `apiFetch`. */
function parseEnvelope<T>(status: number, text: string): T {
  let payload: { success?: boolean; data?: T; error?: { code: string; message: string; fieldErrors?: { field: string; message: string }[] }; traceId?: string } | null = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    payload = null;
  }
  // 204 (no body) is a success for uploads that return nothing.
  if (status === 204) return null as T;
  if (!payload || payload.success !== true || status < 200 || status >= 300) {
    const error = payload?.error ?? { code: "UNKNOWN_ERROR", message: "An unexpected error occurred.", fieldErrors: [] };
    throw new ApiClientError({ ...error, fieldErrors: error.fieldErrors ?? [] }, status, payload?.traceId);
  }
  return payload.data as T;
}

/**
 * Multipart POST with upload progress (XHR — `fetch` has no upload progress).
 * Attaches the bearer token like `authorizedFetch`, and retries once with a
 * refreshed token on 401.
 */
function useUploadWithProgress() {
  const { ensureAccessToken } = useAuth();
  return async <T>(path: string, form: FormData, onProgress?: (fraction: number) => void): Promise<T> => {
    const send = (token: string) =>
      new Promise<{ status: number; text: string }>((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.open("POST", `${env.NEXT_PUBLIC_API_BASE_URL}${path}`);
        xhr.withCredentials = true;
        xhr.setRequestHeader("Authorization", `Bearer ${token}`);
        xhr.upload.onprogress = (event) => {
          if (event.lengthComputable && event.total > 0) onProgress?.(event.loaded / event.total);
        };
        xhr.onload = () => resolve({ status: xhr.status, text: xhr.responseText });
        xhr.onerror = () => reject(networkError());
        xhr.onabort = () => reject(networkError());
        xhr.send(form);
      });
    let result = await send(await ensureAccessToken("tenant"));
    if (result.status === 401) {
      onProgress?.(0);
      result = await send(await ensureAccessToken("tenant", { force: true }));
    }
    onProgress?.(1);
    return parseEnvelope<T>(result.status, result.text);
  };
}

/** One file per request: the server's request ceiling is 26 MB (Slice D addendum), files are ≤ 20 MB each. */
function fileForm(file: File, field: "files[]" | "file"): FormData {
  const form = new FormData();
  form.append(field, file);
  return form;
}

/**
 * `POST /exams/{id}/submissions/{studentId}/documents` — staff "Enter answer
 * sheet" on the student's behalf. One file per call (part `files[]`).
 */
export function useUploadSubmissionDocument(examId: string) {
  const upload = useUploadWithProgress();
  const invalidate = useInvalidateManagement(examId);
  return useMutation({
    mutationFn: ({ studentId, file, onProgress }: { studentId: string; file: File; onProgress?: (fraction: number) => void }) =>
      upload<unknown>(`/v1/exams/${enc(examId)}/submissions/${enc(studentId)}/documents`, fileForm(file, "files[]"), onProgress),
    onSuccess: invalidate,
  });
}

/** `DELETE /exams/{id}/submissions/{studentId}/documents/{docId}` — staff only, before marks are published; audit-logged. */
export function useDeleteSubmissionDocument(examId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateManagement(examId);
  return useMutation({
    mutationFn: async ({ studentId, documentId }: { studentId: string; documentId: string }) => {
      try {
        await authorizedFetch<null>(
          "tenant",
          `/v1/exams/${enc(examId)}/submissions/${enc(studentId)}/documents/${enc(documentId)}`,
          { method: "DELETE" }
        );
      } catch (error) {
        // 204 has no envelope; `apiFetch` reports it as status 204, which is success here.
        if (isApiClientError(error) && error.status === 204) return;
        throw error;
      }
    },
    onSuccess: invalidate,
  });
}

/** `POST /exams/{id}/results/{studentId}/marked-paper` — multipart `file`. */
export function useUploadMarkedPaper(examId: string) {
  const upload = useUploadWithProgress();
  const invalidate = useInvalidateManagement(examId);
  return useMutation({
    mutationFn: ({ studentId, file, onProgress }: { studentId: string; file: File; onProgress?: (fraction: number) => void }) =>
      upload<unknown>(`/v1/exams/${enc(examId)}/results/${enc(studentId)}/marked-paper`, fileForm(file, "file"), onProgress),
    onSuccess: invalidate,
  });
}

/**
 * Short-lived signed links (10 min) for staff downloads. Fetched on click and
 * opened immediately — never stored or rendered as a persistent URL.
 */
export function useExamFileLink(examId: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (target: { kind: "answer"; studentId: string; documentId: string } | { kind: "marked"; studentId: string }) =>
      authorizedFetch<ExamDocumentLink>(
        "tenant",
        target.kind === "answer"
          ? `/v1/exams/${enc(examId)}/submissions/${enc(target.studentId)}/documents/${enc(target.documentId)}`
          : `/v1/exams/${enc(examId)}/results/${enc(target.studentId)}/marked-paper`
      ),
  });
}

// ---------------------------------------------------------------------------
// Student — written exam flow and published written/physical results
// ---------------------------------------------------------------------------

/** One of the student's own answer-sheet uploads. `uploadedAt` is read when the server sends it. */
export interface MyAnswerDocument {
  id: string;
  fileName: string;
  uploadedAt?: string | null;
}

/** `GET /student/exams/{id}/paper` → `{ url, fileName }` (signed 10 min); needs the open written attempt (409 `NOT_STARTED` otherwise). */
export type StudentPaperLink = ExamDocumentLink;

/**
 * Published written/physical result: `GET /student/exams/{id}/result` (the
 * backend reuses `ResultResponse`; 409 `NOT_RELEASED` before publication).
 * `markedPaper` is a 10-minute signed link, so it is re-fetched on click.
 */
export interface StudentMarksResult {
  examId: string;
  attemptId: string | null;
  score: number | string | null;
  totalMarks: number | string | null;
  cutoffMarks: number | string | null;
  passed: boolean | null;
  markedPaper?: ExamDocumentLink | null;
  attendance?: Attendance | null;
}

export const studentWrittenKeys = {
  myDocuments: (examId: string) => [...studentExamKeys.all, "my-documents", examId] as const,
  marks: (examId: string) => [...studentExamKeys.all, "marks", examId] as const,
};

function documentList(data: MyAnswerDocument[] | { content?: MyAnswerDocument[]; documents?: MyAnswerDocument[] } | null | undefined) {
  if (!data) return [];
  if (Array.isArray(data)) return data;
  return data.content ?? data.documents ?? [];
}

/**
 * `GET /student/exams/{id}/paper` — WRITTEN, enrolled, with an open attempt
 * (started by `POST /student/exams/{id}/attempts { password }`, which sets the
 * deadline `min(open + duration, endAt)` server-side).
 */
export function useOpenStudentPaper(examId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => authorizedFetch<StudentPaperLink>("tenant", `/v1/student/exams/${enc(examId)}/paper`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: studentExamKeys.detail(examId) });
      void queryClient.invalidateQueries({ queryKey: studentExamKeys.list() });
    },
  });
}

/** `GET /student/exams/{id}/my-documents` — the student's own uploads only. */
export function useMyAnswerDocuments(examId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentWrittenKeys.myDocuments(examId),
    queryFn: async () =>
      documentList(
        await authorizedFetch<MyAnswerDocument[] | { content?: MyAnswerDocument[]; documents?: MyAnswerDocument[] }>(
          "tenant",
          `/v1/student/exams/${enc(examId)}/my-documents`
        )
      ),
    enabled: examId.length > 0 && (options?.enabled ?? true),
    staleTime: 0,
  });
}

/**
 * `POST /student/exams/{id}/answer-documents` — one file per call (part
 * `files[]`); accepted only while the written attempt is open (409
 * `NOT_STARTED` / `DEADLINE_PASSED` / `FINALIZED` otherwise, server-enforced).
 */
export function useUploadAnswerDocument(examId: string) {
  const upload = useUploadWithProgress();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ file, onProgress }: { file: File; onProgress?: (fraction: number) => void }) =>
      upload<unknown>(`/v1/student/exams/${enc(examId)}/answer-documents`, fileForm(file, "files[]"), onProgress),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: studentWrittenKeys.myDocuments(examId) });
      void queryClient.invalidateQueries({ queryKey: studentExamKeys.detail(examId) });
    },
  });
}

/** `GET /student/exams/{id}/result` — published marks for a WRITTEN/PHYSICAL exam. */
export function useStudentMarksResult(examId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentWrittenKeys.marks(examId),
    queryFn: () => authorizedFetch<StudentMarksResult>("tenant", `/v1/student/exams/${enc(examId)}/result`),
    enabled: examId.length > 0 && (options?.enabled ?? true),
    staleTime: 0,
    retry: (count, error) => !(isApiClientError(error) && error.status >= 400 && error.status < 500) && count < 2,
  });
}

// ---------------------------------------------------------------------------
// Shared client-side file pre-checks (UX only — the server sniffs and re-checks)
// ---------------------------------------------------------------------------

export const EXAM_FILE_ACCEPT = ".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg";
export const EXAM_FILE_MAX_BYTES = 20 * 1024 * 1024;
export const EXAM_FILE_MAX_COUNT = 10;

const ALLOWED_TYPES = new Set(["application/pdf", "image/png", "image/jpeg"]);
const ALLOWED_EXTENSIONS = /\.(pdf|png|jpe?g)$/i;

/** Returns a message for a file the server would reject, or `null`. */
export function examFileProblem(file: File): string | null {
  const typeOk = file.type ? ALLOWED_TYPES.has(file.type) : ALLOWED_EXTENSIONS.test(file.name);
  if (!typeOk) return `${file.name}: only PDF, PNG or JPEG files can be uploaded.`;
  if (file.size > EXAM_FILE_MAX_BYTES) return `${file.name}: files must be 20 MB or smaller.`;
  if (file.size === 0) return `${file.name}: the file is empty.`;
  return null;
}

export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** 409 `NOT_RELEASED` / `NOT_PUBLISHED`: marks exist but aren't published to the student yet. */
export function isResultNotPublishedError(error: unknown): boolean {
  return isApiClientError(error) && error.status === 409 && (error.code === "NOT_RELEASED" || error.code === "NOT_PUBLISHED");
}

export interface SequentialUploadProgress {
  index: number;
  total: number;
  fileName: string;
  fraction: number;
}

/**
 * Uploads `files` one request at a time, reporting per-file progress.
 * Returns which files failed (with messages) so the caller can keep them for
 * a retry. Stops early when `shouldStop(error)` is true (e.g. deadline passed).
 */
export async function uploadSequentially(
  files: File[],
  uploadOne: (file: File, onProgress: (fraction: number) => void) => Promise<unknown>,
  onProgress: (progress: SequentialUploadProgress) => void,
  shouldStop?: (error: unknown) => boolean
): Promise<{ uploaded: File[]; failed: { file: File; error: unknown }[]; stoppedBy: unknown | null }> {
  const uploaded: File[] = [];
  const failed: { file: File; error: unknown }[] = [];
  for (let index = 0; index < files.length; index += 1) {
    const file = files[index];
    onProgress({ index, total: files.length, fileName: file.name, fraction: 0 });
    try {
      await uploadOne(file, (fraction) => onProgress({ index, total: files.length, fileName: file.name, fraction }));
      uploaded.push(file);
    } catch (error) {
      if (shouldStop?.(error)) {
        failed.push(...files.slice(index).map((rest) => ({ file: rest, error })));
        return { uploaded, failed, stoppedBy: error };
      }
      failed.push({ file, error });
    }
  }
  return { uploaded, failed, stoppedBy: null };
}
