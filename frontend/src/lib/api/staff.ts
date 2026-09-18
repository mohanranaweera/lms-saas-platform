"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for `user-management`'s Staff Management
 * endpoints (MVP-005, `STAFF-1`,
 * `backend/.../usermanagement/staff/web/StaffController.java`). Follows
 * `lib/api/students.ts`'s exact conventions (`/v1/...` paths — no `/api`
 * prefix, `NEXT_PUBLIC_API_BASE_URL` already ends in `/api`; every call goes
 * through `useAuth().authorizedFetch("tenant", ...)`).
 *
 * No update/delete/deactivate/password-reset endpoint exists on the backend
 * for this module yet (see `StaffController`'s own class javadoc) — this
 * client intentionally exposes only the three real endpoints below.
 */

/** Mirrors `StaffResponse` field-for-field. `status` mirrors `tenant_user.status` (`"ACTIVE" | "SUSPENDED"` in practice), treated as an open string so an unrecognized value degrades gracefully. */
export interface StaffResponse {
  id: string;
  name: string;
  email: string;
  roleCode: string;
  status: string;
}

/**
 * Mirrors `StaffCreateRequest` field-for-field. `roleCode` must be one of
 * the 7 assignable staff sub-role codes (`FINANCE_STAFF`,
 * `COURSE_COORDINATOR`, `STUDENT_SUPPORT`, `CONTENT_MANAGER`,
 * `EXAM_MANAGER`, `ATTENDANCE_OPERATOR`, `READ_ONLY_AUDITOR`) — deliberately
 * excludes `TENANT_ADMIN`/`TEACHER`/`TEACHER_ASSISTANT`/`STUDENT`, which are
 * provisioned by other flows, not this one. `lib/api/roles.ts#useAssignableRoles`
 * is this form's source for the actual selectable set, not a hardcoded copy
 * of this list.
 */
export interface StaffCreateInput {
  name: string;
  email: string;
  password: string;
  roleCode: string;
}

const staffListKey = ["staff"] as const;
const staffDetailKey = (id: string) => ["staff", id] as const;

/**
 * `GET /v1/staff` — `STAFF_AND_ROLES`/`VIEW` (Tenant Admin, Read-only
 * Auditor); every other role gets a real `403`, surfaced via
 * `QueryStateBoundary` exactly like any other permission-denied case.
 * `lib/auth/permissions.ts#canViewStaff` is UX convenience only, never the
 * enforcement point.
 */
export function useStaffList() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffListKey,
    queryFn: () => authorizedFetch<StaffResponse[]>("tenant", "/v1/staff"),
  });
}

/** `GET /v1/staff/{id}` — same `STAFF_AND_ROLES`/`VIEW` grant as the list. */
export function useStaffMember(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffDetailKey(id),
    queryFn: () => authorizedFetch<StaffResponse>("tenant", `/v1/staff/${id}`),
    enabled: Boolean(id),
  });
}

/** `POST /v1/staff` — `STAFF_AND_ROLES`/`CREATE_EDIT` (Tenant Admin only, server-enforced). Every new staff account starts with `mustChangePassword = true`, no client choice. */
export function useCreateStaff() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: StaffCreateInput) =>
      authorizedFetch<StaffResponse>("tenant", "/v1/staff", {
        method: "POST",
        body: JSON.stringify(input),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: staffListKey });
    },
  });
}
