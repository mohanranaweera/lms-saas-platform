"use client";

import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";
import { isApiClientError, type ApiClientError } from "@/lib/api/error";

/**
 * Result-pane rendering of a failed `POST /runs` (shared by RPT-01 and
 * RPT-02). `narrowHint` is the report-specific advice for a
 * `REPORT_TOO_LARGE` rejection, e.g. "Narrow the period or select fewer
 * teachers" (no trailing full stop).
 */
export function ReportRunError({
  error,
  narrowHint,
  fieldLabels,
  onRetry,
}: {
  error: unknown;
  narrowHint: string;
  fieldLabels: Record<string, string>;
  onRetry: () => void;
}) {
  if (isApiClientError(error)) {
    if (error.status === 403) {
      return <PermissionDeniedState error={error as ApiClientError} dashboardHref="/tenant-admin/dashboard" />;
    }
    if (error.status === 422 && error.code === "REPORT_VALIDATION") {
      return (
        <EmptyState
          title="Check the filters"
          description="The server rejected some filters. Fix the highlighted fields and search again."
        />
      );
    }
    if (error.status === 422 && error.code === "REPORT_TOO_LARGE") {
      return (
        <ErrorState
          message={`${error.message.replace(/[.\s]+$/, "")}. ${narrowHint}, then search again.`}
          code={error.code}
          fieldErrors={error.fieldErrors}
          fieldLabels={fieldLabels}
        />
      );
    }
    if (error.status === 429) {
      return (
        <ErrorState
          message="Too many report requests, please wait a moment and try again."
          code={error.code}
          onRetry={onRetry}
        />
      );
    }
    if (error.status === 503) {
      return (
        <ErrorState
          message="Reports are temporarily unavailable. Please try again shortly."
          code={error.code}
          onRetry={onRetry}
        />
      );
    }
    return <ErrorState message={error.message} code={error.code} onRetry={onRetry} />;
  }
  return <ErrorState message="The report could not be generated. Please try again." onRetry={onRetry} />;
}

/** Plain-language copy for a failed run, used for the live-region announcement. */
export function reportRunErrorMessage(error: unknown, narrowHint: string): string {
  if (isApiClientError(error)) {
    if (error.status === 403) return "You don't have permission to view this report.";
    if (error.status === 429) return "Too many report requests, please wait a moment and try again.";
    if (error.status === 503) return "Reports are temporarily unavailable. Please try again shortly.";
    if (error.code === "REPORT_TOO_LARGE") return `The report is too large. ${narrowHint}.`;
    return error.message;
  }
  return "Please try again.";
}
