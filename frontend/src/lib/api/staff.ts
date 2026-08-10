import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * Typed client + React Query hooks for Staff Management (MVP-005)'s real,
 * shipped backend surface: `POST /v1/staff`, `GET /v1/staff`,
 * `GET /v1/staff/{id}` (`backend/src/main/java/com/lms/usermanagement/staff/web/StaffController.java`).
 *
 * Path convention: the base URL (`NEXT_PUBLIC_API_BASE_URL`) already includes
 * `/api`, so every path here omits it — matching `lib/api/auth.ts`'s
 * convention (`/v1/auth/login`, etc.), NOT `tenant-registrations.ts`'s
 * `/api/v1/tenant-registrations` (a pre-existing, separately-reported bug —
 * do not copy that pattern).
 *
 * There is deliberately no update/delete/role-edit/password-reset function
 * here — the backend exposes none of those endpoints for staff accounts in
 * this pass (see StaffController's doc comment).
 */

/** Mirrors `StaffResponse` (`web/dto/StaffResponse.java`) exactly. */
export interface StaffAccount {
  id: string;
  name: string;
  email: string;
  roleCode: string;
  /** `"ACTIVE" | "SUSPENDED"` today (`identityaccessservice.domain.AccountStatus`),
   * typed as `string` here since this module treats it as opaque display data. */
  status: string;
}

/** Mirrors `StaffCreateRequest` (`web/dto/StaffCreateRequest.java`) exactly. */
export interface StaffCreateInput {
  name: string;
  email: string;
  password: string;
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
): Promise<StaffAccount> {
  return authorizedFetch<StaffAccount>("tenant", STAFF_LIST_PATH, {
    method: "POST",
    body: JSON.stringify(input),
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
