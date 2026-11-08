"use client";

import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "@/lib/api/courses";
import { myPermissionsKey } from "@/lib/api/my-permissions";

/**
 * Typed client + React Query hooks for Staff Management (STAFF-MGMT-02 §2.5;
 * `backend/.../usermanagement/staff/web/StaffController.java`,
 * `identityaccessservice/web/PermissionMatrixController.java`). Paths are
 * `/v1/...` (the API base URL already ends in `/api`); every call goes through
 * `useAuth().authorizedFetch("tenant", ...)`. No tenant id is ever sent: the
 * server resolves it from the authenticated context.
 */

/** Server-computed UI hints for the current actor (`StaffResponse.Actions`). */
export interface StaffActions {
  canEdit: boolean;
  canChangeRole: boolean;
  canDeactivate: boolean;
  canReactivate: boolean;
  canResendInvite: boolean;
}

/** Mirrors `StaffResponse`. `status` is INVITED / ACTIVE / SUSPENDED (open string). */
export interface StaffResponse {
  id: string;
  firstName: string | null;
  lastName: string | null;
  name: string;
  email: string;
  contactNumber: string | null;
  roleCode: string;
  roleName: string | null;
  status: string;
  lastLoginAt: string | null;
  actions: StaffActions;
}

/** Mirrors `StaffCreateRequest` - no password (the invitee sets it from the account-setup email). */
export interface StaffCreateInput {
  firstName: string;
  lastName: string;
  email: string;
  mobileNumber: string;
  roleCode: string;
}

/** Mirrors `StaffUpdateRequest` (profile only). */
export interface StaffUpdateInput {
  firstName: string;
  lastName: string;
  contactNumber: string;
}

/** Mirrors `AssignableRole`. */
export interface AssignableRole {
  code: string;
  displayName: string;
}

export type MatrixCellState = "GRANTED" | "NOT_GRANTED" | "LOCKED" | "UNAVAILABLE";

/** Mirrors `PermissionMatrixView` and its nested records. */
export interface PermissionMatrixCell {
  state: MatrixCellState | string;
  reason: string | null;
}

export interface PermissionMatrixRow {
  key: string;
  label: string;
  availability: string;
  reason: string | null;
  cells: Record<string, PermissionMatrixCell>;
}

export interface PermissionMatrixGroup {
  key: string;
  label: string;
  rows: PermissionMatrixRow[];
}

export interface PermissionMatrix {
  version: number;
  roles: AssignableRole[];
  groups: PermissionMatrixGroup[];
}

export interface PermissionChange {
  roleCode: string;
  capabilityKey: string;
  granted: boolean;
}

/** Mirrors `StaffActivityService.StaffActivity`. `details` is already redacted server-side. */
export interface StaffActivity {
  id: string;
  action: string;
  actionLabel: string;
  actorName: string;
  actorRole: string | null;
  targetEntity: string | null;
  targetReference: string | null;
  occurredAt: string;
  details: Record<string, unknown> | null;
}

export interface StaffListParams {
  q: string;
  page: number;
  size: number;
}

export interface StaffActivityParams {
  q: string;
  /** ISO instant or empty. */
  from: string;
  /** ISO instant or empty. */
  to: string;
}

export const staffKeys = {
  list: (params: StaffListParams) => ["staff-list", params] as const,
  listRoot: ["staff-list"] as const,
  assignableRoles: ["staff-assignable-roles"] as const,
  matrix: ["staff-permission-matrix"] as const,
  activities: (params: StaffActivityParams) => ["staff-activities", params] as const,
  activitiesRoot: ["staff-activities"] as const,
};

export const ACTIVITY_PAGE_SIZE = 20;

/** `GET /v1/staff?q&page&size` - STAFF_VIEW; server search over name/email/phone. */
export function useStaffList(params: StaffListParams) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffKeys.list(params),
    queryFn: () => {
      const search = new URLSearchParams({ page: String(params.page), size: String(params.size) });
      if (params.q) search.set("q", params.q);
      return authorizedFetch<PageResponse<StaffResponse>>("tenant", `/v1/staff?${search.toString()}`);
    },
    placeholderData: keepPreviousData,
  });
}

/** `GET /v1/staff/assignable-roles` - STAFF_MANAGE; Operator / Manager today. */
export function useAssignableStaffRoles(options: { enabled?: boolean } = {}) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffKeys.assignableRoles,
    queryFn: () => authorizedFetch<AssignableRole[]>("tenant", "/v1/staff/assignable-roles"),
    enabled: options.enabled ?? true,
    staleTime: 5 * 60_000,
  });
}

function useStaffMutation<TInput>(request: (input: TInput) => { path: string; init: RequestInit }) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) => {
      const { path, init } = request(input);
      return authorizedFetch<StaffResponse>("tenant", path, init);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: staffKeys.listRoot });
      void queryClient.invalidateQueries({ queryKey: staffKeys.activitiesRoot });
    },
  });
}

/** `POST /v1/staff` - STAFF_MANAGE; creates an INVITED account and sends the setup email. 409 = email taken in this tenant. */
export function useCreateStaff() {
  return useStaffMutation((input: StaffCreateInput) => ({
    path: "/v1/staff",
    init: { method: "POST", body: JSON.stringify(input) },
  }));
}

/** `PATCH /v1/staff/{id}` - STAFF_MANAGE; name and contact number only. */
export function useUpdateStaff() {
  return useStaffMutation(({ id, input }: { id: string; input: StaffUpdateInput }) => ({
    path: `/v1/staff/${encodeURIComponent(id)}`,
    init: { method: "PATCH", body: JSON.stringify(input) },
  }));
}

/** `PUT /v1/staff/{id}/role` - STAFF_ASSIGN_ROLES; never self (server-enforced). */
export function useChangeStaffRole() {
  return useStaffMutation(({ id, roleCode }: { id: string; roleCode: string }) => ({
    path: `/v1/staff/${encodeURIComponent(id)}/role`,
    init: { method: "PUT", body: JSON.stringify({ roleCode }) },
  }));
}

export type StaffLifecycleAction = "deactivate" | "reactivate" | "resend-invite";

/** `POST /v1/staff/{id}/deactivate|reactivate|resend-invite` - STAFF_MANAGE. */
export function useStaffLifecycle() {
  return useStaffMutation(({ id, action }: { id: string; action: StaffLifecycleAction }) => ({
    path: `/v1/staff/${encodeURIComponent(id)}/${action}`,
    init: { method: "POST" },
  }));
}

/** `GET /v1/staff/permission-matrix` - STAFF_VIEW. */
export function usePermissionMatrix(options: { enabled?: boolean } = {}) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffKeys.matrix,
    queryFn: () => authorizedFetch<PermissionMatrix>("tenant", "/v1/staff/permission-matrix"),
    // `enabled: false` = observe the cache only (the page's unsaved-edits guard).
    enabled: options.enabled ?? true,
    // Never refetch behind an editor's back; Reload is explicit.
    refetchOnWindowFocus: false,
  });
}

/**
 * `PUT /v1/staff/permission-matrix` - STAFF_PERMISSIONS_EDIT. Atomic; 409
 * `PERMISSIONS_STALE` when `expectedVersion` is behind. On success the
 * returned matrix replaces the cache and my own permissions are refetched.
 */
export function useSavePermissionMatrix() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { expectedVersion: number; changes: PermissionChange[] }) =>
      authorizedFetch<PermissionMatrix>("tenant", "/v1/staff/permission-matrix", {
        method: "PUT",
        body: JSON.stringify(input),
      }),
    onSuccess: (matrix) => {
      queryClient.setQueryData(staffKeys.matrix, matrix);
      void queryClient.invalidateQueries({ queryKey: staffKeys.matrix });
      void queryClient.invalidateQueries({ queryKey: myPermissionsKey });
      void queryClient.invalidateQueries({ queryKey: staffKeys.activitiesRoot });
    },
  });
}

/** `GET /v1/staff/activities?q&from&to&page&size` - STAFF_ACTIVITY_VIEW; "Load more" pagination. */
export function useStaffActivities(params: StaffActivityParams) {
  const { authorizedFetch } = useAuth();
  return useInfiniteQuery({
    queryKey: staffKeys.activities(params),
    initialPageParam: 0,
    queryFn: ({ pageParam }) => {
      const search = new URLSearchParams({ page: String(pageParam), size: String(ACTIVITY_PAGE_SIZE) });
      if (params.q) search.set("q", params.q);
      if (params.from) search.set("from", params.from);
      if (params.to) search.set("to", params.to);
      return authorizedFetch<PageResponse<StaffActivity>>("tenant", `/v1/staff/activities?${search.toString()}`);
    },
    getNextPageParam: (last) => (last.page + 1 < last.totalPages ? last.page + 1 : undefined),
  });
}
