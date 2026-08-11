import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for Staff Management (MVP-005)'s real,
 * shipped backend surface: `POST /v1/staff`, `GET /v1/staff`,
 * `GET /v1/staff/{id}`, `PATCH /v1/staff/{id}`
 * (`backend/src/main/java/com/lms/usermanagement/staff/web/StaffController.java`).
 *
 * Path convention: the base URL (`NEXT_PUBLIC_API_BASE_URL`) already includes
 * `/api`, so every path here omits it — matching `lib/api/auth.ts`'s
 * convention (`/v1/auth/login`, etc.), NOT `tenant-registrations.ts`'s
 * `/api/v1/tenant-registrations` (a pre-existing, separately-reported bug —
 * do not copy that pattern).
 *
 * There is deliberately no delete/deactivate/password-reset function here —
 * the backend exposes none of those endpoints for staff accounts in this
 * pass (see StaffController's doc comment). Role editing (`PATCH`) is now
 * supported; see `updateStaffRole`/`useUpdateStaffRole` below.
 */

/** Mirrors `StaffResponse` (`web/dto/StaffResponse.java`) exactly. Never
 * carries a password field — the raw generated password is only ever
 * present, once, on `StaffCreated` (the `POST /v1/staff` response). */
export interface StaffAccount {
  id: string;
  name: string;
  email: string;
  roleCode: string;
  /** `"ACTIVE" | "SUSPENDED"` today (`identityaccessservice.domain.AccountStatus`),
   * typed as `string` here since this module treats it as opaque display data. */
  status: string;
}

/**
 * Mirrors `POST /v1/staff`'s response shape exactly: the same fields as
 * `StaffAccount` plus `temporaryPassword`, the raw backend-generated
 * password. Returned exactly once, on this create response only — never on
 * `GET /v1/staff` or `GET /v1/staff/{id}` — so this type is deliberately
 * distinct from `StaffAccount` rather than an optional field bolted onto it.
 */
export interface StaffCreated extends StaffAccount {
  temporaryPassword: string;
}

/** Mirrors `StaffCreateRequest` (`web/dto/StaffCreateRequest.java`) exactly.
 * No `password` field — the backend generates it and returns it once via
 * `StaffCreated.temporaryPassword`. */
export interface StaffCreateInput {
  name: string;
  email: string;
  roleCode: string;
}

/** Body for `PATCH /v1/staff/{id}` — the only mutable field this endpoint
 * exposes is the staff member's role. */
export interface StaffRoleUpdateInput {
  roleCode: string;
}

/**
 * Matches `AuthContextValue["authorizedFetch"]` (`lib/auth/auth-context.tsx`)
 * structurally, extracted via a type query rather than duplicated by hand so
 * the two can never silently drift apart.
 */
type AuthorizedFetch = ReturnType<typeof useAuth>["authorizedFetch"];

const STAFF_LIST_PATH = "/v1/staff";

function staffDetailPath(id: string): string {
  return `${STAFF_LIST_PATH}/${id}`;
}

/** Shared query-key namespace so `useCreateStaff`'s success handler can
 * invalidate every staff-list query without hand-matching key arrays. */
export const staffQueryKeys = {
  all: ["staff"] as const,
  list: () => [...staffQueryKeys.all, "list"] as const,
  detail: (id: string) => [...staffQueryKeys.all, "detail", id] as const,
};

export function listStaff(authorizedFetch: AuthorizedFetch): Promise<StaffAccount[]> {
  return authorizedFetch<StaffAccount[]>("tenant", STAFF_LIST_PATH);
}

export function getStaff(authorizedFetch: AuthorizedFetch, id: string): Promise<StaffAccount> {
  return authorizedFetch<StaffAccount>("tenant", staffDetailPath(id));
}

export function createStaff(
  authorizedFetch: AuthorizedFetch,
  input: StaffCreateInput
): Promise<StaffCreated> {
  return authorizedFetch<StaffCreated>("tenant", STAFF_LIST_PATH, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateStaffRole(
  authorizedFetch: AuthorizedFetch,
  id: string,
  roleCode: string
): Promise<StaffAccount> {
  return authorizedFetch<StaffAccount>("tenant", staffDetailPath(id), {
    method: "PATCH",
    body: JSON.stringify({ roleCode } satisfies StaffRoleUpdateInput),
  });
}

export function useStaffList() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffQueryKeys.list(),
    queryFn: () => listStaff(authorizedFetch),
  });
}

export function useStaff(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffQueryKeys.detail(id),
    queryFn: () => getStaff(authorizedFetch, id),
    enabled: id.length > 0,
  });
}

export function useCreateStaff() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: StaffCreateInput) => createStaff(authorizedFetch, input),
    onSuccess: () => {
      // Refetches the staff list so a newly created row appears without a
      // manual refetch call from the caller.
      queryClient.invalidateQueries({ queryKey: staffQueryKeys.list() });
    },
  });
}

export function useUpdateStaffRole(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (roleCode: string) => updateStaffRole(authorizedFetch, id, roleCode),
    onSuccess: () => {
      // A role change affects both this staff member's detail view and their
      // row/badge on the list view — invalidate both rather than just one.
      queryClient.invalidateQueries({ queryKey: staffQueryKeys.detail(id) });
      queryClient.invalidateQueries({ queryKey: staffQueryKeys.list() });
    },
  });
}
