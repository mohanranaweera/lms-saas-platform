"use client";

import { useCallback } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { env } from "@/lib/env";
import { ApiClientError, isApiClientError } from "./error";
import type { ApiResponse } from "./types";
import type { PageResponse } from "./courses";
import type { ImpersonationStartResponse } from "./impersonation";
import { zonedDateTimeToIso } from "@/components/courses/admin/tenant-time";

/** Friendly copy for STU-MGMT-02 / ADR-033 error codes; falls back to the server message. */
export function studentErrorMessage(error: unknown, fallback = "Something went wrong. Please try again."): string {
  if (!isApiClientError(error)) return fallback;
  switch (error.code) {
    case "IMPERSONATION_READ_ONLY":
      return "This is a read-only support session. Changes can't be made while viewing as a student.";
    case "IMPERSONATION_NOT_ALLOWED":
      return "A support session can't be started for this student (or from inside another support session).";
    case "IMPERSONATION_SESSION_INVALID":
      return "The support session has ended. Start a new one from the students list.";
    case "STUDENT_NOT_ACTIVE":
      return "This student isn't active (pending approval or suspended), so this action isn't available.";
    default:
      return error.fieldErrors[0]?.message ?? error.message ?? fallback;
  }
}

function nextDay(date: string): string {
  const [y, m, d] = date.split("-").map(Number);
  const next = new Date(Date.UTC(y, m - 1, d + 1));
  return next.toISOString().slice(0, 10);
}

/**
 * Inclusive `YYYY-MM-DD` range in the tenant zone → ISO instants for the
 * remarks endpoint: `from` = start of the first day, `to` = the last
 * millisecond of the last day.
 */
export function dateRangeToInstants(range: DateRange, timeZone: string): { from?: string; to?: string } {
  const from = range.from ? (zonedDateTimeToIso(range.from, "00:00", timeZone) ?? undefined) : undefined;
  let to: string | undefined;
  if (range.to) {
    const end = zonedDateTimeToIso(nextDay(range.to), "00:00", timeZone);
    to = end ? new Date(Date.parse(end) - 1).toISOString() : undefined;
  }
  return { from, to };
}

/**
 * STU-MGMT-02 (Klass student-management parity) client — contract:
 * `docs/specs/student-management-parity.md` §5. Paths have no `/api` prefix
 * (`NEXT_PUBLIC_API_BASE_URL` already ends in `/api`). Every call goes through
 * `useAuth().authorizedFetch`/`ensureAccessToken`; the tenant always comes from
 * the server-side auth context, never from anything sent here.
 */

export type DirectoryTab = "ACTIVE" | "PENDING";
export type DirectoryField = "ALL" | "INDEX" | "FIRST_NAME" | "LAST_NAME" | "EMAIL" | "MOBILE";

export const DIRECTORY_FIELD_OPTIONS: Array<{ value: DirectoryField; label: string }> = [
  { value: "ALL", label: "All Fields" },
  { value: "INDEX", label: "Index Number" },
  { value: "FIRST_NAME", label: "First Name" },
  { value: "LAST_NAME", label: "Last Name" },
  { value: "EMAIL", label: "Email" },
  { value: "MOBILE", label: "Mobile" },
];

export const DIRECTORY_MAX_PAGE_SIZE = 100;
export const QR_EXPORT_LIMIT = 500;

/** `StudentDirectoryRow`. Index/mobile are strings (leading zeros kept). */
export interface StudentDirectoryRow {
  id: string;
  indexNumber: string | null;
  firstName: string | null;
  lastName: string | null;
  email: string;
  mobile: string | null;
  lastLoginAt: string | null;
  status: string;
  hasPhoto: boolean;
  /** Not in the §5 row contract; shown by the Bulk Print QRs dialog only if a server ever sends it. */
  nic?: string | null;
}

/** `StudentDetail` — every §3 field plus derived/display fields. */
export interface StudentDetail {
  id: string;
  indexNumber: string | null;
  email: string;
  firstName: string | null;
  lastName: string | null;
  fullName: string;
  whatsappNumber: string | null;
  dateOfBirth: string | null;
  mobile: string | null;
  addressLine: string | null;
  addressLine2: string | null;
  city: string | null;
  postalCode: string | null;
  guardianName: string | null;
  guardianPhone: string | null;
  guardianNic: string | null;
  fixedPhone: string | null;
  school: string | null;
  alYear: number | null;
  stream: string | null;
  status: string;
  pending: boolean;
  registeredAt: string | null;
  lastLoginAt: string | null;
  photoUrl?: string | null;
  enrolledCourseIds: string[];
}

/** `StudentRegistrationForm` (create) — no tenantId/status/role/index. Blank optional fields are sent as `null`. */
export interface StudentRegistrationInput {
  email: string;
  password: string | null;
  firstName: string;
  lastName: string;
  whatsappNumber: string | null;
  dateOfBirth: string | null;
  mobile: string;
  addressLine: string | null;
  addressLine2: string | null;
  city: string | null;
  postalCode: string | null;
  guardianName: string | null;
  guardianPhone: string | null;
  guardianNic: string | null;
  courseIds: string[];
  fixedPhone: string | null;
  school: string | null;
  alYear: number | null;
  stream: string | null;
}

/** `PUT /students/{id}/detail` body — same form minus password (and index, which is server-owned). */
export type StudentDetailUpdateInput = Omit<StudentRegistrationInput, "password">;

export interface StudentCreateResult {
  student: StudentDetail;
  /** Present only when the password was left blank; shown once, never stored. */
  temporaryPassword?: string | null;
}

export interface StudentRemark {
  id: string;
  body: string;
  authorName: string;
  createdAt: string;
}

export interface SignedPhotoUrl {
  url: string;
  expiresAt: string;
}

export interface DirectoryParams {
  tab: DirectoryTab;
  field: DirectoryField;
  q: string;
  page: number;
  size: number;
}

export interface DateRange {
  from?: string;
  to?: string;
}

export const studentMgmtKeys = {
  directoryAll: ["students", "directory"] as const,
  directory: (params: DirectoryParams) => ["students", "directory", params] as const,
  detail: (id: string) => ["students", id, "detail"] as const,
  photo: (id: string) => ["students", id, "photo"] as const,
  ownPhoto: ["students", "me", "photo"] as const,
  remarksAll: (id: string) => ["students", id, "remarks"] as const,
  remarks: (id: string, range: DateRange) => ["students", id, "remarks", range] as const,
};

export function directoryQueryString(params: Partial<DirectoryParams>): string {
  const search = new URLSearchParams();
  if (params.tab) search.set("tab", params.tab);
  if (params.field) search.set("field", params.field);
  if (params.q && params.q.trim()) search.set("q", params.q.trim());
  if (params.page !== undefined) search.set("page", String(params.page));
  if (params.size !== undefined) search.set("size", String(Math.min(params.size, DIRECTORY_MAX_PAGE_SIZE)));
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

/** `GET /students/directory` — server-side tab/field/q search, paginated, sorted by index. */
export function useStudentDirectory(params: DirectoryParams, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentMgmtKeys.directory(params),
    queryFn: ({ signal }) =>
      authorizedFetch<PageResponse<StudentDirectoryRow>>(
        "tenant",
        `/v1/students/directory${directoryQueryString(params)}`,
        { signal }
      ),
    placeholderData: keepPreviousData,
    enabled: options?.enabled ?? true,
  });
}

/**
 * Fetches the ids of every student matching the current search, page by
 * page, up to `QR_EXPORT_LIMIT` (the export cap). Used by the Bulk Print QRs
 * dialog's header checkbox — "all matching", not just the visible page.
 */
export function useFetchAllDirectoryIds() {
  const { authorizedFetch } = useAuth();
  return useCallback(
    async (filters: { tab: DirectoryTab; field: DirectoryField; q: string }) => {
      const ids: string[] = [];
      let page = 0;
      let total = 0;
      for (;;) {
        const data = await authorizedFetch<PageResponse<StudentDirectoryRow>>(
          "tenant",
          `/v1/students/directory${directoryQueryString({ ...filters, page, size: DIRECTORY_MAX_PAGE_SIZE })}`
        );
        total = data.totalElements;
        for (const row of data.content) {
          if (ids.length < QR_EXPORT_LIMIT) ids.push(row.id);
        }
        page += 1;
        if (ids.length >= QR_EXPORT_LIMIT || page >= data.totalPages || data.content.length === 0) break;
      }
      return { ids, total, capped: total > ids.length };
    },
    [authorizedFetch]
  );
}

/** `GET /students/{id}/detail` — keyed by id; aborts when the selection changes. */
export function useStudentDetail(id: string | null, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentMgmtKeys.detail(id ?? ""),
    queryFn: ({ signal }) => authorizedFetch<StudentDetail>("tenant", `/v1/students/${id}/detail`, { signal }),
    enabled: Boolean(id) && (options?.enabled ?? true),
  });
}

function invalidateStudent(queryClient: ReturnType<typeof useQueryClient>, id?: string) {
  void queryClient.invalidateQueries({ queryKey: studentMgmtKeys.directoryAll });
  // The legacy flat list (`GET /v1/students`, e.g. the broadcast picker) too.
  void queryClient.invalidateQueries({ queryKey: ["students"], exact: true });
  if (id) {
    void queryClient.invalidateQueries({ queryKey: studentMgmtKeys.detail(id) });
    void queryClient.invalidateQueries({ queryKey: ["students", id, "enrollments"] });
  }
}

/** `POST /students/v2` — 201 `{student, temporaryPassword?}`. */
export function useRegisterStudent() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: StudentRegistrationInput) =>
      authorizedFetch<StudentCreateResult>("tenant", "/v1/students/v2", {
        method: "POST",
        body: JSON.stringify(input),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(studentMgmtKeys.detail(data.student.id), data.student);
      invalidateStudent(queryClient);
    },
  });
}

/** `PUT /students/{id}/detail` — no password, no index; courses are add-only. */
export function useUpdateStudentDetail(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: StudentDetailUpdateInput) =>
      authorizedFetch<StudentDetail>("tenant", `/v1/students/${id}/detail`, {
        method: "PUT",
        body: JSON.stringify(input),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(studentMgmtKeys.detail(id), data);
      invalidateStudent(queryClient, id);
      void queryClient.invalidateQueries({ queryKey: ["students", id] });
    },
  });
}

/** `POST /students/{id}/approve` (pending → active). */
export function useApproveStudent() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<StudentDetail>("tenant", `/v1/students/${id}/approve`, { method: "POST" }),
    onSuccess: (data) => {
      queryClient.setQueryData(studentMgmtKeys.detail(data.id), data);
      invalidateStudent(queryClient, data.id);
    },
  });
}

/** `DELETE /students/{id}` `{reason}` — soft removal from this tenant; history kept server-side. 204. */
export function useDeleteStudent() {
  const { ensureAccessToken } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, reason }: { id: string; reason: string }) => {
      const response = await sendWithAuth(ensureAccessToken, `/v1/students/${id}`, {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reason }),
      });
      if (!response.ok) throw await toApiError(response);
      return id;
    },
    onSuccess: (id) => {
      queryClient.removeQueries({ queryKey: studentMgmtKeys.detail(id) });
      invalidateStudent(queryClient);
    },
  });
}

// ---------------------------------------------------------------------------
// Profile photo (§6)
// ---------------------------------------------------------------------------

export type PhotoOwner = { kind: "staff"; studentId: string } | { kind: "self" };

function photoPath(owner: PhotoOwner): string {
  return owner.kind === "self" ? "/v1/students/me/photo" : `/v1/students/${owner.studentId}/photo`;
}

function photoKey(owner: PhotoOwner) {
  return owner.kind === "self" ? studentMgmtKeys.ownPhoto : studentMgmtKeys.photo(owner.studentId);
}

/** `GET …/photo` → short-lived signed URL, or `null` when the student has no photo (404). */
export function useStudentPhoto(owner: PhotoOwner, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: photoKey(owner),
    queryFn: async ({ signal }) => {
      try {
        return await authorizedFetch<SignedPhotoUrl>("tenant", photoPath(owner), { signal });
      } catch (error) {
        if (isApiClientError(error) && error.status === 404) return null;
        throw error;
      }
    },
    // Signed URLs live 5 minutes server-side; refetch before they expire.
    staleTime: 4 * 60 * 1000,
    refetchInterval: 4 * 60 * 1000,
    retry: false,
    enabled: options?.enabled ?? true,
  });
}

/** `POST …/photo` (multipart `file`). */
export function useUploadStudentPhoto(owner: PhotoOwner) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: Blob) => {
      const form = new FormData();
      form.append("file", file, "profile.jpg");
      return authorizedFetch<unknown>("tenant", photoPath(owner), { method: "POST", body: form });
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: photoKey(owner) });
      if (owner.kind === "staff") invalidateStudent(queryClient, owner.studentId);
    },
  });
}

/** `DELETE …/photo` → 204. */
export function useRemoveStudentPhoto(owner: PhotoOwner) {
  const { ensureAccessToken } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const response = await sendWithAuth(ensureAccessToken, photoPath(owner), { method: "DELETE" });
      if (!response.ok) throw await toApiError(response);
    },
    onSuccess: () => {
      queryClient.setQueryData(photoKey(owner), null);
      if (owner.kind === "staff") invalidateStudent(queryClient, owner.studentId);
    },
  });
}

// ---------------------------------------------------------------------------
// Remarks (staff-only)
// ---------------------------------------------------------------------------

/** `GET /students/{id}/remarks?page&size[&from&to]` — from/to are ISO instants (tenant-zone day bounds). */
export function useStudentRemarks(id: string, range: DateRange = {}, timeZone = "UTC") {
  const { authorizedFetch } = useAuth();
  const instants = dateRangeToInstants(range, timeZone);
  return useQuery({
    queryKey: studentMgmtKeys.remarks(id, instants),
    queryFn: ({ signal }) => {
      const search = new URLSearchParams({ page: "0", size: "50" });
      if (instants.from) search.set("from", instants.from);
      if (instants.to) search.set("to", instants.to);
      return authorizedFetch<PageResponse<StudentRemark>>(
        "tenant",
        `/v1/students/${id}/remarks?${search.toString()}`,
        { signal }
      );
    },
    enabled: id.length > 0,
  });
}

export function useAddStudentRemark(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: string) =>
      authorizedFetch<StudentRemark>("tenant", `/v1/students/${id}/remarks`, {
        method: "POST",
        body: JSON.stringify({ body }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: studentMgmtKeys.remarksAll(id) });
    },
  });
}

// ---------------------------------------------------------------------------
// Impersonation start (ADR-033)
// ---------------------------------------------------------------------------

/** `POST /students/{id}/impersonation` `{reason}` — sets the HttpOnly handoff cookie. */
export function useStartImpersonation() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      authorizedFetch<ImpersonationStartResponse>("tenant", `/v1/students/${id}/impersonation`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
  });
}

// ---------------------------------------------------------------------------
// Binary downloads (CSV, QR PNG, QR PDF)
// ---------------------------------------------------------------------------

type EnsureToken = (kind: "tenant", options?: { force?: boolean }) => Promise<string>;

async function sendWithAuth(ensureAccessToken: EnsureToken, path: string, init: RequestInit): Promise<Response> {
  const url = `${env.NEXT_PUBLIC_API_BASE_URL}${path}`;
  const attempt = async (token: string) => {
    try {
      return await fetch(url, {
        credentials: "include",
        ...init,
        headers: { ...(init.headers as Record<string, string> | undefined), Authorization: `Bearer ${token}` },
      });
    } catch {
      throw new ApiClientError(
        {
          code: "NETWORK_ERROR",
          message: "Unable to reach the server. Check your connection and try again.",
          fieldErrors: [],
        },
        0
      );
    }
  };
  let response = await attempt(await ensureAccessToken("tenant"));
  if (response.status === 401) {
    const error = await toApiError(response.clone());
    if (error.code === "UNAUTHENTICATED" || error.code === "SESSION_REVOKED") {
      response = await attempt(await ensureAccessToken("tenant", { force: true }));
    }
  }
  return response;
}

async function toApiError(response: Response): Promise<ApiClientError> {
  let payload: ApiResponse<unknown> | null = null;
  try {
    payload = (await response.json()) as ApiResponse<unknown>;
  } catch {
    payload = null;
  }
  return new ApiClientError(
    payload?.error ?? {
      code: "DOWNLOAD_FAILED",
      message: "The file could not be downloaded. Please try again.",
      fieldErrors: [],
    },
    response.status,
    payload?.traceId
  );
}

/** Authorized binary fetch (bearer in a header — never a token in a URL). */
export function useAuthorizedBinary() {
  const { ensureAccessToken } = useAuth();
  return useCallback(
    async (path: string, init: RequestInit = {}): Promise<Blob> => {
      const response = await sendWithAuth(ensureAccessToken, path, init);
      if (!response.ok) throw await toApiError(response);
      return response.blob();
    },
    [ensureAccessToken]
  );
}

export function saveBlobAs(blob: Blob, filename: string): void {
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = objectUrl;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 30_000);
}

/** `GET /students/directory/export.csv` with the current filters. */
export function useExportDirectoryCsv() {
  const fetchBinary = useAuthorizedBinary();
  return useMutation({
    mutationFn: async (filters: { tab: DirectoryTab; field: DirectoryField; q: string }) => {
      const blob = await fetchBinary(`/v1/students/directory/export.csv${directoryQueryString(filters)}`, {
        headers: { Accept: "text/csv, application/json" },
      });
      saveBlobAs(blob, `students-${filters.tab.toLowerCase()}.csv`);
    },
  });
}

/** `POST /students/qr-export` `{studentIds}` → PDF label sheet. */
export function useExportStudentQrs() {
  const fetchBinary = useAuthorizedBinary();
  return useMutation({
    mutationFn: async (studentIds: string[]) => {
      const blob = await fetchBinary("/v1/students/qr-export", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/pdf, application/json" },
        body: JSON.stringify({ studentIds }),
      });
      saveBlobAs(blob, "student-qr-labels.pdf");
      return studentIds.length;
    },
  });
}

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

/**
 * `GET /students/{id}/qr.png` (bearer-authorized). Returned as the Blob (for
 * Download PNG) plus an in-memory data URL for display/print — no object URL
 * lifecycle to manage and no token or stable URL in the page.
 */
export function useStudentQrPng(id: string) {
  const fetchBinary = useAuthorizedBinary();
  return useQuery({
    queryKey: ["students", id, "qr.png"],
    queryFn: async () => {
      const blob = await fetchBinary(`/v1/students/${id}/qr.png`, { headers: { Accept: "image/png, application/json" } });
      return { blob, dataUrl: await blobToDataUrl(blob) };
    },
    enabled: id.length > 0,
    staleTime: 5 * 60 * 1000,
  });
}
