"use client";

import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";

/**
 * Result-pane notices shared by every report workspace (RPT-01, RPT-02). Both
 * carry `id="export-disabled-reason"` so disabled export buttons can point at
 * them with `aria-describedby`.
 */

export const EXPORT_DISABLED_REASON_ID = "export-disabled-reason";

/** The filters differ from the run's request: the figures shown are for the previous filters. */
export function StaleNotice() {
  return (
    <div
      id={EXPORT_DISABLED_REASON_ID}
      role="status"
      className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100"
      data-testid="stale-notice"
    >
      <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
      <span>
        The filters have changed since this report was run. The figures below are for the previous
        filters - search again to refresh them and to export.
      </span>
    </div>
  );
}

/** The server no longer holds the run (404 on export/paging): offer to run the same filters again. */
export function ExpiredNotice({ onRunAgain }: { onRunAgain: () => void }) {
  return (
    <div
      id={EXPORT_DISABLED_REASON_ID}
      role="status"
      className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:border-amber-700 dark:bg-amber-950 dark:text-amber-100"
      data-testid="expired-notice"
    >
      <span className="flex items-start gap-2">
        <AlertTriangle className="mt-0.5 size-4 shrink-0" aria-hidden="true" />
        This report has expired — run it again.
      </span>
      <Button type="button" size="sm" variant="outline" onClick={onRunAgain}>
        Run again
      </Button>
    </div>
  );
}
