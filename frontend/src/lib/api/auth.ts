import { apiFetch } from "./client";

/**
 * Typed client for identity-access-service's auth endpoints
 * (docs/api/identity-access-service.md). Tenant-scoped users (Student, Teacher,
 * Tenant Admin, Staff) and Platform Admin authenticate through structurally
 * separate paths — never a shared controller — so every function here takes an
 * explicit `PrincipalKind` rather than guessing which path to call.
 */
export type PrincipalKind = "tenant" | "platform-admin";

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  expiresInSeconds: number;
  sessionId: string;
  mustChangePassword: boolean;
}

export interface RefreshResponse {
  accessToken: string;
  expiresInSeconds: number;
}

interface AuthPaths {
  login: string;
  refresh: string;
  logout: string;
}

/**
 * Exported so `AuthProvider` (lib/auth/auth-context.tsx) can drive the same
 * refresh/retry helper for the logout call without duplicating path strings.
 */
export const authPaths: Record<PrincipalKind, AuthPaths> = {
  tenant: {
    login: "/v1/auth/login",
    refresh: "/v1/auth/refresh",
    logout: "/v1/auth/logout",
  },
  "platform-admin": {
    login: "/v1/platform-admin/auth/login",
    refresh: "/v1/platform-admin/auth/refresh",
    logout: "/v1/platform-admin/auth/logout",
  },
};

export function login(kind: PrincipalKind, body: LoginRequest): Promise<LoginResponse> {
  return apiFetch<LoginResponse>(authPaths[kind].login, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

/**
 * Reads the `lms_refresh_token` / `lms_platform_admin_refresh_token` `HttpOnly`
 * cookie server-side — there is nothing to pass in the body. Relies on
 * `apiFetch`'s `credentials: "include"` default to send it.
 */
export function refresh(kind: PrincipalKind): Promise<RefreshResponse> {
  return apiFetch<RefreshResponse>(authPaths[kind].refresh, {
    method: "POST",
  });
}
