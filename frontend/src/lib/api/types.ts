/**
 * Types mirroring the backend's response envelope (`com.lms.common.api`).
 *
 * Every endpoint any domain module exposes returns this exact shape. Keep this file
 * in lockstep with the backend types — if a real endpoint's response ever diverges
 * from this shape, that is an API mismatch to report, not something to work around
 * with a cast in a consuming component.
 */

export interface FieldError {
  field: string;
  message: string;
}

export interface ApiError {
  /** e.g. "VALIDATION_ERROR", "NOT_FOUND", "CONFLICT", "UNAUTHENTICATED", "FORBIDDEN", "INTERNAL_ERROR" */
  code: string;
  message: string;
  /** Always an array, never null. */
  fieldErrors: FieldError[];
}

export interface ApiResponse<T> {
  success: boolean;
  data: T | null;
  error: ApiError | null;
  /** ISO instant. */
  timestamp: string;
  traceId: string;
}
