"use client";

import Link from "next/link";
import { CalendarDays, ChartColumn, EllipsisVertical, Pencil, Timer, Trash2 } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  EXAM_TYPE_LABELS,
  ExamLiveStatusChip,
  ExamTypeIcon,
  formatExamMarks,
} from "@/components/exams/config/exam-shared";
import { formatDuration, formatExamDateTime, relativeToServerNow } from "@/lib/exams/exam-time";
import type { ExamCard as ExamCardData } from "@/lib/api/exam-config";
import { cn } from "@/lib/utils";

function percent(part: number, whole: number): number {
  if (!whole || whole <= 0) return 0;
  return Math.min(100, Math.max(0, Math.round((part / whole) * 100)));
}

/**
 * One exam in the directory grid (Klass card): type, status, ⋮ menu,
 * schedule with a relative phrase computed from the server's clock, and the
 * participation counts the server reports (0 until Slice C/D record them).
 */
export function ExamCard({
  exam,
  serverNow,
  manageHref,
  statsHref,
  canEdit,
  canDelete,
  deleteBlockedReason,
  onEdit,
  onDelete,
}: {
  exam: ExamCardData;
  serverNow: string;
  manageHref: string;
  statsHref: string;
  canEdit: boolean;
  canDelete: boolean;
  /** Set after the server refused a delete (409) — the item is shown disabled with this reason. */
  deleteBlockedReason: string | null;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const headingId = `exam-card-${exam.id}`;
  const attendedPct = percent(exam.attended, exam.enrolled);
  const duration = formatDuration(exam.durationMinutes);
  const extent =
    exam.type === "MCQ"
      ? exam.questionCount !== null
        ? `${exam.questionCount} ${exam.questionCount === 1 ? "question" : "questions"}`
        : null
      : formatExamMarks(exam.totalMarks);
  const outcome =
    exam.type === "MCQ"
      ? { value: exam.completed, label: "completed" }
      : exam.type === "WRITTEN"
        ? { value: exam.submitted, label: "submitted" }
        : null;

  return (
    <article
      aria-labelledby={headingId}
      data-testid="exam-card"
      data-exam-id={exam.id}
      className="flex w-full min-w-0 flex-col gap-3 rounded-xl bg-card p-4 text-card-foreground shadow-sm ring-1 ring-foreground/10"
    >
      <div className="flex items-center gap-2">
        <ExamTypeIcon type={exam.type} />
        <span className="text-sm font-semibold tracking-wide text-muted-foreground uppercase" data-testid="exam-card-type">
          {EXAM_TYPE_LABELS[exam.type]}
        </span>
        <ExamLiveStatusChip status={exam.status} />
        {canEdit || canDelete ? (
          <DropdownMenu>
            <DropdownMenuTrigger
              render={<Button type="button" variant="ghost" size="icon-sm" className="ml-auto" />}
              aria-label={`Actions for ${exam.name}`}
              data-testid="exam-card-menu"
            >
              <EllipsisVertical aria-hidden="true" />
            </DropdownMenuTrigger>
            <DropdownMenuContent data-testid="exam-card-menu-content">
              {canEdit ? (
                <DropdownMenuItem onClick={onEdit} data-testid="exam-action-edit">
                  <Pencil className="text-primary" aria-hidden="true" />
                  Edit
                </DropdownMenuItem>
              ) : null}
              {canDelete ? (
              <DropdownMenuItem
                variant="destructive"
                onClick={onDelete}
                disabled={deleteBlockedReason !== null}
                data-testid="exam-action-delete"
                className="items-start"
              >
                <Trash2 className="mt-0.5" aria-hidden="true" />
                <span className="flex flex-col">
                  Delete
                  {deleteBlockedReason ? (
                    <span className="max-w-56 text-xs font-normal text-muted-foreground" data-testid="exam-delete-blocked">
                      {deleteBlockedReason}
                    </span>
                  ) : null}
                </span>
              </DropdownMenuItem>
              ) : null}
            </DropdownMenuContent>
          </DropdownMenu>
        ) : null}
      </div>

      <div className="flex min-w-0 flex-col gap-0.5">
        <h3 id={headingId} className="text-base font-semibold break-words text-foreground" data-testid="exam-card-name">
          {exam.name}
        </h3>
        <p className="text-sm break-words text-muted-foreground" data-testid="exam-card-course">
          {exam.courseName}
        </p>
      </div>

      <div className="flex flex-col gap-1.5 text-sm">
        <p className="flex flex-wrap items-center gap-x-1.5" data-testid="exam-card-when">
          <CalendarDays className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <time dateTime={exam.startAt} className="text-foreground">
            {formatExamDateTime(exam.startAt)}
          </time>
          <span className="text-muted-foreground">· {relativeToServerNow(exam.startAt, serverNow)}</span>
        </p>
        <p className="flex flex-wrap items-center gap-x-1.5" data-testid="exam-card-extent">
          <Timer className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <span className="text-foreground">{duration ?? "No fixed duration"}</span>
          {extent ? <span className="text-muted-foreground">· {extent}</span> : null}
        </p>
      </div>

      <div className="mt-auto flex flex-col gap-1.5">
        <div className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
          <span data-testid="exam-card-attended">
            <strong className="font-semibold text-foreground">{exam.attended}</strong> / {exam.enrolled} attended ·{" "}
            {attendedPct}%
          </span>
          {outcome ? (
            <span data-testid="exam-card-outcome">
              <strong className="font-semibold text-foreground">{outcome.value}</strong> {outcome.label}
            </span>
          ) : null}
        </div>
        <div
          role="progressbar"
          aria-label={`${exam.name}: attended`}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={attendedPct}
          className="h-1.5 w-full overflow-hidden rounded-full bg-primary/15"
        >
          <div className="h-full rounded-full bg-primary transition-[width]" style={{ width: `${attendedPct}%` }} />
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <Link
          href={manageHref}
          className={cn(buttonVariants({ size: "sm" }), "uppercase tracking-wide")}
          aria-label={`Manage ${exam.name}`}
          data-testid="exam-card-manage"
        >
          Manage
        </Link>
        <Link
          href={statsHref}
          className={cn(buttonVariants({ variant: "ghost", size: "sm" }), "text-primary uppercase tracking-wide")}
          aria-label={`Stats for ${exam.name}`}
          data-testid="exam-card-stats"
        >
          <ChartColumn aria-hidden="true" />
          Stats
        </Link>
      </div>
    </article>
  );
}
