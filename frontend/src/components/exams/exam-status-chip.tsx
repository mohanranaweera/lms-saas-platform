import { CalendarClock, CheckCircle2, Lock, PencilLine } from "lucide-react";
import { cn } from "@/lib/utils";
import type { ExamStatus } from "@/lib/api/exams";

/**
 * Exam status chip — never color alone (`.claude/rules/ui-ux.md` §4): every
 * status pairs an icon with a text label, following
 * `components/courses/course-status-badge.tsx`/
 * `components/attendance/attendance-status-chip.tsx`'s exact chip-styling
 * precedent. Fills the `Scheduled`/`Closed` gap flagged in the MVP-017 plan
 * §11 (the component-library spec currently only documents `Draft`/
 * `Published`).
 */
export const EXAM_STATUS_LABELS: Record<ExamStatus, string> = {
  DRAFT: "Draft",
  SCHEDULED: "Scheduled",
  PUBLISHED: "Published",
  CLOSED: "Closed",
};

const STATUS_STYLES: Record<ExamStatus, { icon: typeof CheckCircle2; className: string }> = {
  DRAFT: { icon: PencilLine, className: "text-muted-foreground" },
  SCHEDULED: { icon: CalendarClock, className: "text-foreground" },
  PUBLISHED: { icon: CheckCircle2, className: "text-foreground" },
  CLOSED: { icon: Lock, className: "text-muted-foreground" },
};

export function ExamStatusChip({ status }: { status: ExamStatus }) {
  const { icon: Icon, className } = STATUS_STYLES[status];
  return (
    <span
      className={cn(
        "inline-flex w-fit items-center gap-1.5 rounded-md border border-border bg-muted px-2 py-0.5 text-xs font-medium",
        className
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {EXAM_STATUS_LABELS[status]}
    </span>
  );
}
