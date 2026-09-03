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

const studentsListKey = ["students"] as const;
const studentDetailKey = (id: string) => ["students", id] as const;
const ownProfileKey = ["students", "me"] as const;

function invalidateStudentLists(queryClient: QueryClient) {
  return queryClient.invalidateQueries({ queryKey: studentsListKey });
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
