import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for `identity-access-service`'s Teacher
 * Management endpoints (`backend/.../usermanagement/teacher/web/TeacherController.java`,
 * MVP-007 TCH-1). Every endpoint requires an authenticated Tenant/Course
 * Coordinator/Student Support/Read-only Auditor session (`PrincipalKind` "tenant"),
 * so — unlike `tenant-registrations.ts`'s anonymous `registerTenant` — these are
 * not bare exported functions: each hook calls `useAuth()` internally to obtain
 * `authorizedFetch` and closes over it, since `authorizedFetch` needs the `kind`
 * param and only exists on the auth context.
 */

/**
 * `SUSPENDED` (Wave 3, master instruction §11) is a second, independent
 * transition pair layered on top of the original `PENDING -> APPROVED |
 * REJECTED` approval workflow — only reachable via `APPROVED -> SUSPENDED`
 * and back, never from `PENDING`/`REJECTED` (see `ApprovalStatus`'s own
 * backend doc comment).
 */
export type TeacherApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "SUSPENDED";
export type TeacherAccountStatus = "ACTIVE" | "SUSPENDED";

/** Mirrors `TeacherResponse` exactly (backend `.../teacher/web/dto/TeacherResponse.java`). */
export interface Teacher {
  id: string;
  userId: string;
  name: string;
  firstName: string | null;
  lastName: string | null;
  email: string;
  contactNumber: string | null;
  instituteCommissionPercent: number;
  qualifications: string | null;
  description: string | null;
  profileMediaFileId: string | null;
  profileImageUrl: string | null;
  publicProfileDisabled: boolean;
  displayOrder?: number | null;
  studentCount: number;
  approvalStatus: TeacherApprovalStatus;
  accountStatus: TeacherAccountStatus;
  approvedBy: string | null;
  approvedAt: string | null;
}

export interface TeacherCreateInput {
  name: string;
  firstName: string;
  lastName: string;
  email: string;
  password: string;
  contactNumber: string;
  instituteCommissionPercent: number;
  qualifications?: string;
  description?: string;
  profileMediaFileId?: string;
  publicProfileDisabled: boolean;
}

export interface TeacherUpdateInput {
  firstName: string;
  lastName: string;
  contactNumber: string;
  instituteCommissionPercent: number;
  qualifications?: string;
  description?: string;
  profileMediaFileId?: string;
  publicProfileDisabled: boolean;
}

export interface TeacherPage {
  content: Teacher[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

const TEACHERS_KEY = ["teachers"] as const;
const teacherKey = (id: string) => ["teachers", id] as const;

/**
 * List hook — always fetches the full, unfiltered `/v1/teachers` list.
 * `approvalStatus` filtering is intentionally applied client-side by callers
 * (the list page), not via the query param this endpoint supports: a Tenant
 * Admin/Course Coordinator/Student Support/Read-only Auditor is already fully
 * authorized to view every status under the same `VIEW` grant, so there's no
 * under-authorized data being over-fetched here (contrast
 * `.claude/rules/ui-ux.md` §1's Teacher-role restriction, which does not apply
 * to this admin-side view).
 */
export function useTeachers(options?: { enabled?: boolean; query?: string; page?: number; size?: number }) {
  const { authorizedFetch } = useAuth();
  const query = options?.query?.trim() ?? "";
  const page = options?.page ?? 0;
  const size = options?.size ?? 10;
  const paged = Boolean(query || page > 0 || options?.size);
  const path = `/v1/teachers?q=${encodeURIComponent(query)}&page=${page}&size=${size}`;
  return useQuery({
    queryKey: [...TEACHERS_KEY, { query, page, size }],
    queryFn: async () => {
      const response = await authorizedFetch<TeacherPage | Teacher[]>("tenant", paged ? path : "/v1/teachers");
      return Array.isArray(response) ? response : response.content;
    },
    enabled: options?.enabled ?? true,
    placeholderData: (previous) => previous,
  });
}

export function useTeacher(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: teacherKey(id),
    queryFn: () => authorizedFetch<Teacher>("tenant", `/v1/teachers/${id}`),
    enabled: Boolean(id),
  });
}

export function useCreateTeacher() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TeacherCreateInput) =>
      authorizedFetch<Teacher>("tenant", "/v1/teachers", {
        method: "POST",
        body: JSON.stringify(input),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: TEACHERS_KEY });
    },
  });
}

export function useReorderTeachers() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (teacherIds: string[]) => authorizedFetch<null>("tenant", "/v1/teachers/reorder", {
      method: "POST", body: JSON.stringify({ teacherIds }),
    }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: TEACHERS_KEY }),
  });
}

export function useUpdateTeacher(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TeacherUpdateInput) =>
      authorizedFetch<Teacher>("tenant", `/v1/teachers/${id}`, {
        method: "PATCH",
        body: JSON.stringify(input),
      }),
    onSuccess: (data) => {
      queryClient.setQueryData(teacherKey(id), data);
      queryClient.invalidateQueries({ queryKey: TEACHERS_KEY });
    },
  });
}

export function useApproveTeacher() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<Teacher>("tenant", `/v1/teachers/${id}/approve`, {
        method: "POST",
      }),
    onSuccess: (data, id) => {
      queryClient.invalidateQueries({ queryKey: TEACHERS_KEY });
      queryClient.invalidateQueries({ queryKey: teacherKey(id) });
    },
  });
}

export function useRejectTeacher() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<Teacher>("tenant", `/v1/teachers/${id}/reject`, {
        method: "POST",
      }),
    onSuccess: (data, id) => {
      queryClient.invalidateQueries({ queryKey: TEACHERS_KEY });
      queryClient.invalidateQueries({ queryKey: teacherKey(id) });
    },
  });
}

/**
 * `POST /v1/teachers/{id}/suspend` (Wave 3, PAR-04-04) — same gate as
 * approve/reject (`TEACHERS`/`CREATE_EDIT` + `requireTenantAdmin()`,
 * enforced server-side in `TeacherService`). Only legal from `APPROVED`
 * (409 otherwise).
 */
export function useSuspendTeacher() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<Teacher>("tenant", `/v1/teachers/${id}/suspend`, {
        method: "POST",
      }),
    onSuccess: (data, id) => {
      queryClient.invalidateQueries({ queryKey: TEACHERS_KEY });
      queryClient.invalidateQueries({ queryKey: teacherKey(id) });
    },
  });
}

/** `POST /v1/teachers/{id}/reactivate` — inverse of `useSuspendTeacher`. Only legal from `SUSPENDED` (409 otherwise). */
export function useReactivateTeacher() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<Teacher>("tenant", `/v1/teachers/${id}/reactivate`, {
        method: "POST",
      }),
    onSuccess: (data, id) => {
      queryClient.invalidateQueries({ queryKey: TEACHERS_KEY });
      queryClient.invalidateQueries({ queryKey: teacherKey(id) });
    },
  });
}
