"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";

/**
 * ADR-031 sign-in mobile endpoints. Each path is one account's number:
 * `/v1/teachers/{id}/login-mobile` (TEACHERS), `/v1/staff/{id}/login-mobile`
 * (STAFF_AND_ROLES), or `/v1/auth/me/login-mobile` (the signed-in non-student
 * user's own). The backend normalises the number, enforces per-institute
 * uniqueness (`409 MOBILE_ALREADY_REGISTERED`, field `loginMobile`) and
 * audit-logs every change.
 */
export interface LoginMobile {
  /** E.164 (`+94771234567`), or `null` = email sign-in only. */
  loginMobile: string | null;
}

export type LoginMobilePath =
  | `/v1/teachers/${string}/login-mobile`
  | `/v1/staff/${string}/login-mobile`
  | "/v1/auth/me/login-mobile";

const key = (path: LoginMobilePath) => ["login-mobile", path] as const;

export function useLoginMobile(path: LoginMobilePath) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: key(path),
    queryFn: () => authorizedFetch<LoginMobile>("tenant", path),
  });
}

/** `null`/blank removes mobile sign-in for the account. */
export function useSaveLoginMobile(path: LoginMobilePath) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (loginMobile: string | null) =>
      authorizedFetch<LoginMobile>("tenant", path, { method: "PUT", body: JSON.stringify({ loginMobile }) }),
    onSuccess: (data) => queryClient.setQueryData(key(path), data),
  });
}
