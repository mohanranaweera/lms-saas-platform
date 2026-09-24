"use client";

import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client for `user-management`'s Student Management endpoints
 * (MVP-006, `/v1/students/**`, no `/api` prefix — mirrors `lib/api/auth.ts`'s
 * convention; `NEXT_PUBLIC_API_BASE_URL` already ends in `/api`, so a bare
 * `/v1/...` path is correct here. Other modules previously had a doubled
 * `/api/v1/...` prefix bug from not following this convention; that has
 * since been fixed — see git history for the affected files).
 *
 * Every one of these endpoints requires an authenticated caller (staff
 * endpoints gated by the `STUDENTS` domain area, `/me` endpoints gated by
 * `hasRole('STUDENT')`), so every request goes through `useAuth().authorizedFetch`
 * rather than the bare `apiFetch` — there is no anonymous student data to
 * fetch. `kind` is always `"tenant"`: Student/Teacher/Staff/Tenant Admin all
 * authenticate through the same `PrincipalKind` (see `lib/api/auth.ts`).
 *
 * No delete, bulk-import, self-registration, teacher-roster, or history
 * endpoint exists on the backend for this module yet (see
 * `StudentController`'s own class javadoc) — this client intentionally
 * exposes only the six real endpoints below.
 */

export interface StudentResponse {
  id: string;
  name: string;
  email: string;
  roleCode: string;
  /** Mirrors `tenant_user.status` — `"ACTIVE" | "SUSPENDED"` in practice, but treated as an open string so an unrecognized value degrades gracefully rather than breaking a type guard. */
  status: string;
}

export interface StudentCreateInput {
  name: string;
  email: string;
  password: string;
}

export interface StudentUpdateInput {
  name: string;
}

/** Mirrors `TemporaryPasswordResponse` — the one-time temp password, never persisted beyond the mutation's own result. */
export interface TemporaryPasswordResponse {
  temporaryPassword: string;
}

/** Mirrors `StudentEnrollRequest` (now owned by `payment-management`, same `/v1/students/{id}/enroll` path/contract — see `ManualEnrollmentController`'s class javadoc, ADR-016). `reason` is mandatory. */
export interface StudentEnrollInput {
  courseId: string;
  reason: string;
}

/** Mirrors `BulkImportRowResultResponse` — one row of `POST /v1/students/bulk-import`'s response body. */
export interface BulkImportRowResult {
  row: number;
  status: "CREATED" | "FAILED";
  reason: string | null;
  studentId: string | null;
}

const studentsListKey = ["students"] as const;
const studentDetailKey = (id: string) => ["students", id] as const;
const ownProfileKey = ["students", "me"] as const;

function invalidateStudentLists(queryClient: QueryClient) {
  // `exact: true` is load-bearing, not cosmetic: `invalidateQueries` fuzzy-
  // matches by key *prefix* by default, and both `studentDetailKey(id)`
  // (`["students", id]`) and `ownProfileKey` (`["students", "me"]`) share
  // `studentsListKey`'s (`["students"]`) prefix. Without `exact: true`, a
  // mutation on one student (e.g. activate/deactivate) would also invalidate
  // every *other* mounted student's detail query and force a refetch of it —
  // and since that refetch reads from the server, not the mutation's own
  // response, it can race with (and silently overwrite) the `setQueryData`
  // call each of those mutations already makes to apply the fresh result
  // immediately, flipping the visible status back to stale data.
  return queryClient.invalidateQueries({ queryKey: studentsListKey, exact: true });
}

/** `GET /v1/students` — full tenant-scoped list, no pagination/server-side filter (see StudentController). */
export function useStudents() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentsListKey,
    queryFn: () => authorizedFetch<StudentResponse[]>("tenant", "/v1/students"),
  });
}

/** `GET /v1/students/{id}` — uniform 404 for both nonexistent and cross-tenant ids. */
export function useStudent(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentDetailKey(id),
    queryFn: () => authorizedFetch<StudentResponse>("tenant", `/v1/students/${id}`),
    enabled: Boolean(id),
  });
}

/** `POST /v1/students` — Tenant Admin + Student Support only (server-enforced). */
export function useCreateStudent() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: StudentCreateInput) =>
      authorizedFetch<StudentResponse>("tenant", "/v1/students", {
        method: "POST",
        body: JSON.stringify(input),
      }),
    onSuccess: () => invalidateStudentLists(queryClient),
  });
}

/** `PATCH /v1/students/{id}` — `name` only; Tenant Admin + Student Support only (server-enforced). */
export function useUpdateStudent(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: StudentUpdateInput) =>
      authorizedFetch<StudentResponse>("tenant", `/v1/students/${id}`, {
        method: "PATCH",
        body: JSON.stringify(input),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(studentDetailKey(id), data);
      void invalidateStudentLists(queryClient);
    },
  });
}

/** `GET /v1/students/me` — `hasRole('STUDENT')` only; never takes an id. */
export function useOwnStudentProfile() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: ownProfileKey,
    queryFn: () => authorizedFetch<StudentResponse>("tenant", "/v1/students/me"),
  });
}

/** `PATCH /v1/students/me` — `name` only; `hasRole('STUDENT')` only; never takes an id. */
export function useUpdateOwnStudentProfile() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: StudentUpdateInput) =>
      authorizedFetch<StudentResponse>("tenant", "/v1/students/me", {
        method: "PATCH",
        body: JSON.stringify(input),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(ownProfileKey, data);
    },
  });
}

/**
 * `POST /v1/students/{id}/activate` — staff `STUDENTS`/`CREATE_EDIT` (Wave 3,
 * PAR-03-05). Thin wrapper over `UserProvisioningApi.activateTenantUser`, now
 * audit-logged server-side. Updates both the detail cache entry and the list
 * cache directly from the response rather than just invalidating, so the
 * Actions area reflects the new status without an extra round-trip.
 */
export function useActivateStudent(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<StudentResponse>("tenant", `/v1/students/${id}/activate`, {
        method: "POST",
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(studentDetailKey(id), data);
      void invalidateStudentLists(queryClient);
    },
  });
}

/** `POST /v1/students/{id}/deactivate` — staff `STUDENTS`/`CREATE_EDIT`. See `useActivateStudent`'s doc comment — same shape, inverse direction. */
export function useDeactivateStudent(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<StudentResponse>("tenant", `/v1/students/${id}/deactivate`, {
        method: "POST",
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(studentDetailKey(id), data);
      void invalidateStudentLists(queryClient);
    },
  });
}

/**
 * `POST /v1/students/{id}/reset-password` — staff `STUDENTS`/`CREATE_EDIT`.
 * Returns the one-time temporary password exactly once in the response —
 * callers must show it in a dismissible, copy-once dialog and must never
 * persist it in any state beyond that dialog's own local state (per
 * `.claude/rules/security.md`). No cache write here: this mutation does not
 * change any field `StudentResponse` renders.
 */
export function useResetStudentPassword(id: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: () =>
      authorizedFetch<TemporaryPasswordResponse>("tenant", `/v1/students/${id}/reset-password`, {
        method: "POST",
      }),
  });
}

/**
 * `POST /v1/students/{id}/enroll` — staff `STUDENTS`/`CREATE_EDIT` (Wave 3,
 * ADR-016 decision 1: a real `Order`+`Payment(CONFIRMED, STAFF_GRANTED)`
 * behind this, not a bypass of the payment/ledger trail). `reason` is
 * mandatory, non-blank. On success, invalidates this student's enrollment
 * history and ledger reads (both change as a result of this call) — those
 * hooks live in `lib/api/enrollments.ts`/`lib/api/ledger.ts`, so this
 * mutation only invalidates by the query-key shape those files export rather
 * than importing their hook functions.
 */
export function useEnrollStudent(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: StudentEnrollInput) =>
      authorizedFetch<null>("tenant", `/v1/students/${id}/enroll`, {
        method: "POST",
        body: JSON.stringify(input),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["students", id, "enrollments"] });
      queryClient.invalidateQueries({ queryKey: ["students", id, "ledger"] });
    },
  });
}

/**
 * `POST /v1/students/bulk-import` — staff `STUDENTS`/`CREATE_EDIT`, multipart
 * (`file` part, CSV). Per-row partial-failure contract (Wave 3 plan §4): every
 * row is attempted independently — a non-empty response is not "all
 * succeeded" or "all failed," callers must render every row's own
 * `status`/`reason`. On success, invalidates the student list (some rows may
 * have created real accounts even if others failed).
 */
export function useBulkImportStudents() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (file: File) => {
      const formData = new FormData();
      formData.append("file", file);
      return authorizedFetch<BulkImportRowResult[]>("tenant", "/v1/students/bulk-import", {
        method: "POST",
        body: formData,
      });
    },
    onSuccess: () => {
      void invalidateStudentLists(queryClient);
    },
  });
}
