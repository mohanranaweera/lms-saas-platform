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

export type TeacherApprovalStatus = "PENDING" | "APPROVED" | "REJECTED";
export type TeacherAccountStatus = "ACTIVE" | "SUSPENDED";

/** Mirrors `TeacherResponse` exactly (backend `.../teacher/web/dto/TeacherResponse.java`). */
export interface Teacher {
  id: string;
  name: string;
  email: string;
  approvalStatus: TeacherApprovalStatus;
  accountStatus: TeacherAccountStatus;
  approvedBy: string | null;
  approvedAt: string | null;
}

export interface TeacherCreateInput {
  name: string;
  email: string;
  password: string;
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
export function useTeachers() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: TEACHERS_KEY,
    queryFn: () => authorizedFetch<Teacher[]>("tenant", "/v1/teachers"),
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
