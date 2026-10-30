"use client";

import type { ComponentType, ReactNode } from "react";
import { CalendarClock, CheckCircle2, ClipboardList, Hourglass, ListChecks, PenTool, PencilLine, School } from "lucide-react";
import { cn } from "@/lib/utils";
import { marksNumber } from "@/lib/api/question-bank";
import type { ExamLiveStatus, ExamType } from "@/lib/api/exam-config";

/** Which portal route tree the exam screens are rendered in. */
export type ExamPortal = "tenant-admin" | "teacher";

export function examPaths(portal: ExamPortal) {
  const base = `/${portal}/exams`;
  return {
    directory: base,
    leaderboards: `${base}/leaderboards`,
    leaderboard: (leaderboardId: string) => `${base}/leaderboards/${leaderboardId}`,
    manage: (examId: string) => `${base}/${examId}`,
    stats: (examId: string) => `${base}/${examId}/stats`,
    dashboard: `/${portal}/dashboard`,
  };
}

export const EXAM_TYPE_LABELS: Record<ExamType, string> = {
  MCQ: "MCQ",
  WRITTEN: "Written",
  PHYSICAL: "Physical",
};

export const EXAM_TYPE_ICONS: Record<ExamType, ComponentType<{ className?: string; "aria-hidden"?: boolean | "true" }>> = {
  MCQ: ListChecks,
  WRITTEN: PenTool,
  PHYSICAL: School,
};

export function ExamTypeIcon({ type, className }: { type: ExamType; className?: string }) {
  const Icon = EXAM_TYPE_ICONS[type] ?? ClipboardList;
  return (
    <span
      className={cn(
        "flex size-8 shrink-0 items-center justify-center rounded-lg border border-primary/20 bg-primary/5 text-primary",
        className
      )}
    >
      <Icon className="size-4" aria-hidden="true" />
    </span>
  );
}

const STATUS: Record<ExamLiveStatus, { label: string; icon: typeof CheckCircle2; className: string }> = {
  DRAFT: { label: "Draft", icon: PencilLine, className: "bg-muted text-muted-foreground" },
  UPCOMING: { label: "Upcoming", icon: CalendarClock, className: "bg-primary/10 text-primary" },
  IN_PROGRESS: { label: "In progress", icon: Hourglass, className: "bg-green-600/10 text-green-700 dark:text-green-400" },
  FINISHED: { label: "Finished", icon: CheckCircle2, className: "bg-muted text-foreground" },
};

/** Server-derived status chip: icon + text, never colour alone. */
export function ExamLiveStatusChip({ status, className }: { status: ExamLiveStatus; className?: string }) {
  const entry = STATUS[status] ?? STATUS.DRAFT;
  const Icon = entry.icon;
  return (
    <span
      data-testid="exam-status-chip"
      className={cn(
        "inline-flex h-6 shrink-0 items-center gap-1 rounded-full px-2 text-xs font-medium whitespace-nowrap",
        entry.className,
        className
      )}
    >
      <Icon className="size-3" aria-hidden="true" />
      {entry.label}
    </span>
  );
}

export function formatExamMarks(value: number | string | null | undefined): string | null {
  if (value === null || value === undefined || value === "") return null;
  const marks = marksNumber(value);
  const text = Number.isInteger(marks) ? String(marks) : String(Number(marks.toFixed(2)));
  return `${text} ${marks === 1 ? "mark" : "marks"}`;
}

export function ExamPageTitle({ title, icon, actions }: { title: string; icon: ReactNode; actions?: ReactNode }) {
  return (
    <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex min-w-0 items-center gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          {icon}
        </span>
        <h1 className="min-w-0 text-xl font-semibold break-words text-foreground">{title}</h1>
      </div>
      {actions}
    </div>
  );
}
