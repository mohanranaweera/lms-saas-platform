import { apiFetch } from "./client";

/**
 * ADR-033 support-impersonation ("Log as Student") auth calls. These are plain
 * functions (not hooks) because `AuthProvider` itself drives them: the
 * impersonation tab never uses `/auth/refresh`.
 *
 * Credentials: the handoff secret is an HttpOnly cookie scoped to
 * `/api/v1/auth/impersonation` (sent via `apiFetch`'s `credentials: "include"`);
 * the returned access token is held in memory only, never in web storage or a URL.
 */

export interface ImpersonatedStudent {
  id: string;
  indexNumber: string | null;
  fullName: string;
}

/** `POST /v1/auth/impersonation/token` response. */
export interface ImpersonationTokenResponse {
  accessToken: string;
  expiresAt: string;
  student: ImpersonatedStudent;
  actorName: string;
  sessionId?: string;
}

/** `POST /v1/students/{id}/impersonation` response. */
export interface ImpersonationStartResponse {
  sessionId: string;
  expiresAt: string;
  student: ImpersonatedStudent;
}

export const impersonationPaths = {
  token: "/v1/auth/impersonation/token",
  end: "/v1/auth/impersonation/end",
  current: "/v1/auth/impersonation/current",
} as const;

export function requestImpersonationToken(): Promise<ImpersonationTokenResponse> {
  return apiFetch<ImpersonationTokenResponse>(impersonationPaths.token, { method: "POST" });
}

/** Ends the support session (cookie and/or bearer). Returns 204 — `apiFetch` would reject a bodiless response, so this uses `fetch` semantics leniently. */
export async function endImpersonationSession(apiBaseUrl: string, accessToken: string | null): Promise<void> {
  await fetch(`${apiBaseUrl}${impersonationPaths.end}`, {
    method: "POST",
    credentials: "include",
    headers: accessToken ? { Authorization: `Bearer ${accessToken}` } : undefined,
  });
}
