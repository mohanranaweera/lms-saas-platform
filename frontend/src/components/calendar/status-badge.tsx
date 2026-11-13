"use client";

import { Ban, CheckCircle2, Clock } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ScheduleStatus } from "@/lib/calendar/status";
import { useT, type TranslateFn } from "@/i18n/i18n-provider";

/** Localized labels (I18N-01); `STATUS_LABELS` stays the English source. */
const STATUS_MESSAGES: Record<ScheduleStatus, (t: TranslateFn) => string> = {
  SCHEDULED: (t) => t("common.scheduleStatus.scheduled"),
  LIVE: (t) => t("common.scheduleStatus.live"),
  OVER: (t) => t("common.scheduleStatus.over"),
  CANCELLED: (t) => t("common.scheduleStatus.cancelled"),
};

export const STATUS_LABELS: Record<ScheduleStatus, string> = {
  SCHEDULED: "Scheduled",
  LIVE: "Live now",
  OVER: "Over",
  CANCELLED: "Cancelled",
};

export const SCHEDULE_BASED_HINT = "Based on the scheduled class time";

/**
 * Schedule status chip: icon + text, never colour alone. "Live now" is the
 * scheduled time window (not meeting telemetry); its pulse dot respects
 * `prefers-reduced-motion`.
 */
export function ScheduleStatusBadge({ status, className }: { status: ScheduleStatus; className?: string }) {
  const t = useT();
  return (
    <span
      data-testid="schedule-status-badge"
      data-status={status}
      className={cn(
        "inline-flex w-fit shrink-0 items-center gap-1.5 rounded-full border px-2 py-0.5 text-xs font-semibold",
        status === "LIVE" && "border-red-200 bg-red-50 text-red-800 dark:border-red-900 dark:bg-red-950/50 dark:text-red-200",
        status === "SCHEDULED" && "border-blue-200 bg-blue-50 text-blue-800 dark:border-blue-900 dark:bg-blue-950/50 dark:text-blue-200",
        status === "OVER" && "border-border bg-muted text-muted-foreground",
        status === "CANCELLED" && "border-amber-300 bg-amber-50 text-amber-900 dark:border-amber-900 dark:bg-amber-950/50 dark:text-amber-200",
        className
      )}
    >
      {status === "LIVE" ? (
        <span className="relative flex size-2" aria-hidden="true">
          <span className="absolute inline-flex size-full rounded-full bg-red-500 opacity-75 motion-safe:animate-ping" />
          <span className="relative inline-flex size-2 rounded-full bg-red-600" />
        </span>
      ) : status === "SCHEDULED" ? (
        <Clock className="size-3.5" aria-hidden="true" />
      ) : status === "OVER" ? (
        <CheckCircle2 className="size-3.5" aria-hidden="true" />
      ) : (
        <Ban className="size-3.5" aria-hidden="true" />
      )}
      {STATUS_MESSAGES[status]?.(t) ?? STATUS_LABELS[status]}
      {status === "LIVE" ? <span className="sr-only"> ({t("common.scheduleStatus.basedOnSchedule")})</span> : null}
    </span>
  );
}
