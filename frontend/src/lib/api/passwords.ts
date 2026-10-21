import { useMutation } from "@tanstack/react-query";
import { env } from "@/lib/env";
import { useAuth } from "@/lib/auth/auth-context";
import { apiFetch } from "./client";
import { ApiClientError } from "./error";

/**
 * AUTH-PWD-01 / ADR-020 — account setup, forgot password, change password.
 * Contract: `docs/api/identity-access-service.md`.
 *
 * The link endpoints are public: the tenant comes from the host, and the
 * one-time token in the link is the only credential. The token is never
 * stored or logged by the frontend — the page reads it from the URL once and
 * removes it from the address bar immediately.
 */

export type PasswordTokenPurpose = "ACCOUNT_SETUP" | "PASSWORD_RESET";

export interface PasswordTokenInfo {
  purpose: PasswordTokenPurpose;
  emailMasked: string;
  expiresAt: string;
}

/** Public. `400 TOKEN_INVALID` for any unusable link (one generic answer). */
export function verifyPasswordToken(token: string): Promise<PasswordTokenInfo> {
  return apiFetch<PasswordTokenInfo>("/v1/auth/password-tokens/verify", {
    method: "POST",
    body: JSON.stringify({ token }),
  });
}

/** Public. `204` on success; no automatic sign-in. */
export function useCompletePasswordToken() {
  return useMutation({
    mutationFn: (input: { token: string; newPassword: string }) =>
      postNoContent("/v1/auth/password-tokens/complete", JSON.stringify(input)),
  });
}

/** Public. Always the same `202` message, whether or not an account exists. */
export function useRequestPasswordReset() {
  return useMutation({
    mutationFn: (email: string) =>
      apiFetch<{ message: string }>("/v1/auth/password-reset/request", {
        method: "POST",
        body: JSON.stringify({ email }),
      }),
  });
}

/** Signed-in tenant user. The current session stays valid; other sessions are signed out by the backend. */
export function useChangePassword() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (input: { currentPassword: string; newPassword: string }) =>
      authorizedFetch<{ accessToken: string; expiresInSeconds: number }>("tenant", "/v1/auth/password", {
        method: "POST",
        body: JSON.stringify(input),
      }),
  });
}

/** A `204 No Content` success has no envelope to parse; errors still use the standard envelope. */
async function postNoContent(path: string, body: string): Promise<void> {
  let response: Response;
  try {
    response = await fetch(`${env.NEXT_PUBLIC_API_BASE_URL}${path}`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json" },
      body,
    });
  } catch {
    throw new ApiClientError(
      { code: "NETWORK_ERROR", message: "Unable to reach the server. Check your connection and try again.", fieldErrors: [] },
      0
    );
  }
  if (response.ok) return;
  let payload: { error?: ConstructorParameters<typeof ApiClientError>[0]; traceId?: string } | null = null;
  try {
    payload = await response.json();
  } catch {
    payload = null;
  }
  throw new ApiClientError(
    payload?.error ?? { code: "UNKNOWN_ERROR", message: "An unexpected error occurred.", fieldErrors: [] },
    response.status,
    payload?.traceId
  );
}
