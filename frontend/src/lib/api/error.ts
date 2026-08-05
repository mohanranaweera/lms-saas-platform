import type { ApiError, FieldError } from "./types";

/**
 * Thrown by `apiFetch` whenever the backend responds with `success: false` or a
 * non-2xx HTTP status. Wraps the backend's `ApiError` payload plus the HTTP status
 * code, so callers (React Query error handlers, `permission-denied-state.tsx`, etc.)
 * can branch on real, server-verified information — never on a client-guessed state.
 */
export class ApiClientError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];
  readonly traceId?: string;

  constructor(apiError: ApiError, status: number, traceId?: string) {
    super(apiError.message);
    this.name = "ApiClientError";
    this.status = status;
    this.code = apiError.code;
    this.fieldErrors = apiError.fieldErrors;
    this.traceId = traceId;
  }
}

export function isApiClientError(error: unknown): error is ApiClientError {
  return error instanceof ApiClientError;
}
