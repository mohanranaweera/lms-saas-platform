import { env } from "@/lib/env";
import { ApiClientError } from "./error";
import type { ApiResponse } from "./types";

/**
 * Generic typed fetch wrapper for the backend's `ApiResponse<T>` envelope.
 *
 * `credentials: "include"` is the default (overridable via `init.credentials`)
 * because identity-access-service delivers the refresh token exclusively via an
 * `HttpOnly` cookie (`lms_refresh_token` / `lms_platform_admin_refresh_token`) —
 * without this, the browser neither stores that cookie on login/refresh responses
 * nor sends it back on `POST /auth/refresh`. See docs/api/identity-access-service.md.
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const url = `${env.NEXT_PUBLIC_API_BASE_URL}${path}`;

  let response: Response;
  try {
    response = await fetch(url, {
      credentials: "include",
      ...init,
      headers: {
        "Content-Type": "application/json",
        ...init?.headers,
      },
    });
  } catch {
    throw new ApiClientError(
      {
        code: "NETWORK_ERROR",
        message: "Unable to reach the server. Check your connection and try again.",
        fieldErrors: [],
      },
      0
    );
  }

  let payload: ApiResponse<T> | null = null;
  try {
    payload = (await response.json()) as ApiResponse<T>;
  } catch {
    payload = null;
  }

  if (!payload || payload.success !== true || !response.ok) {
    const error = payload?.error ?? {
      code: "UNKNOWN_ERROR",
      message: "An unexpected error occurred.",
      fieldErrors: [],
    };

    throw new ApiClientError(error, response.status, payload?.traceId);
  }

  return payload.data as T;
}
