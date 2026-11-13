import { env } from "@/lib/env";
import { getRequestLocale, LOCALE_HEADER } from "@/i18n/locale-store";
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
export interface ApiFetchOptions {
  /**
   * I18N-01: omit the `X-LMS-Locale` header (the i18n catalog requests pass
   * their locale explicitly, or none to let the server resolve it).
   */
  skipLocaleHeader?: boolean;
}

export async function apiFetch<T>(path: string, init?: RequestInit, options?: ApiFetchOptions): Promise<T> {
  const url = `${env.NEXT_PUBLIC_API_BASE_URL}${path}`;

  // A `FormData` body (multipart upload, e.g. material create) must never get
  // a manually-set `Content-Type` — the browser needs to generate its own
  // `multipart/form-data; boundary=...` value, and a fixed
  // `application/json` header here would silently break every multipart
  // caller. Every other call keeps the existing JSON default unchanged.
  const isFormDataBody = init?.body instanceof FormData;

  // I18N-01 (§2 step 1): the UI locale the I18nProvider resolved, so localized
  // API content/messages match the page. Absent until resolution; never
  // persisted server-side.
  const locale = options?.skipLocaleHeader ? null : getRequestLocale();

  let response: Response;
  try {
    response = await fetch(url, {
      credentials: "include",
      ...init,
      headers: {
        ...(isFormDataBody ? {} : { "Content-Type": "application/json" }),
        ...(locale ? { [LOCALE_HEADER]: locale } : {}),
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

  // `204 No Content` (e.g. DELETE translation draft) carries no envelope.
  if (response.status === 204) {
    return undefined as T;
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
