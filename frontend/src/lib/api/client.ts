import { env } from "@/lib/env";
import { ApiClientError } from "./error";
import type { ApiResponse } from "./types";

/**
 * Generic typed fetch wrapper for the backend's `ApiResponse<T>` envelope.
 *
 * This is pure infrastructure: no approved API contract exists yet for any domain
 * (see docs/api/README.md), so nothing in the app calls this yet. It exists so the
 * first real domain module has a single, typed place to make requests from instead
 * of scattering raw `fetch` calls with inline response shapes across components.
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const url = `${env.NEXT_PUBLIC_API_BASE_URL}${path}`;

  let response: Response;
  try {
    response = await fetch(url, {
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
