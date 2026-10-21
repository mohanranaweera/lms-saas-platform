"use client";

import { CalendarClock, Check, ChevronDown, ChevronRight, Circle, Clock, Folder, FolderOpen, Timer } from "lucide-react";
import { cn } from "@/lib/utils";
import type { CourseContent, LessonNode, ModuleNode, PeriodNode } from "@/lib/api/klass-student";
import { ResourceIcon } from "./course-visuals";
import { LessonAccessAction, PayNowButton, StatusPill, accessStatusText } from "./lesson-access-action";
import { formatLessonDateTime, resourceLabel } from "./student-format";

export function periodId(period: PeriodNode): string {
  return `period:${period.key ?? "none"}`;
}

export function moduleId(period: PeriodNode, module: ModuleNode): string {
  return `${periodId(period)}:module:${module.moduleId}`;
}

/**
 * Course content tree (CM-S03..S06): billing period folders (newest first,
 * as ordered by the server) → module folders → lesson rows. Pure projection
 * of the server response: payment badges come from `period.payment.status`
 * and lesson actions from `lesson.access.status`. Expanding a folder records
 * nothing (D14). Expansion state is owned by the page, so it survives tab
 * switches and every dialog (composer, launch, handoff).
 */
export function CourseContentTree({
  content,
  expanded,
  onToggle,
  launchingId,
  onLaunch,
  onPayPeriod,
  onPayLesson,
}: {
  content: CourseContent;
  expanded: ReadonlySet<string>;
  onToggle: (id: string) => void;
  launchingId: string | null;
  onLaunch: (lesson: LessonNode) => void;
  onPayPeriod: ((period: PeriodNode) => void) | null;
  onPayLesson: ((lesson: LessonNode) => void) | null;
}) {
  const percent = content.totalCount > 0 ? Math.round((content.viewedCount / content.totalCount) * 100) : 0;

  return (
    <section
      aria-labelledby="course-content-heading"
      className="overflow-hidden rounded-xl border border-border bg-card shadow-sm"
      data-testid="course-content-panel"
    >
      <div className="flex flex-col gap-2 border-b border-border px-4 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-6">
        <h2 id="course-content-heading" className="text-base font-semibold text-foreground">
          Course content
        </h2>
        <div className="flex items-center gap-3">
          <span className="text-sm text-muted-foreground" data-testid="course-content-progress">
            {content.viewedCount} of {content.totalCount} viewed
          </span>
          <div
            role="progressbar"
            aria-label="Lessons viewed"
            aria-valuemin={0}
            aria-valuemax={content.totalCount}
            aria-valuenow={content.viewedCount}
            className="h-1.5 w-32 overflow-hidden rounded-full bg-green-100 dark:bg-green-950 sm:w-48"
          >
            <div className="h-full rounded-full bg-green-600" style={{ width: `${percent}%` }} />
          </div>
        </div>
      </div>

      {content.periods.length === 0 ? (
        <p className="px-6 py-10 text-center text-sm text-muted-foreground" role="status" data-testid="course-content-empty">
          Your teacher hasn&apos;t added any lessons to this course yet. Check back later.
        </p>
      ) : (
        <ul aria-label="Course content folders" className="divide-y divide-border">
          {content.periods.map((period) => (
            <PeriodFolder
              key={periodId(period)}
              period={period}
              timeZone={content.timezone}
              expanded={expanded}
              onToggle={onToggle}
              launchingId={launchingId}
              onLaunch={onLaunch}
              onPayPeriod={onPayPeriod}
              onPayLesson={onPayLesson}
            />
          ))}
        </ul>
      )}
    </section>
  );
}

function PeriodBadge({ period, onPay }: { period: PeriodNode; onPay: ((period: PeriodNode) => void) | null }) {
  const status = period.payment.status;
  if ((status === "REQUIRED" || status === "PARTIAL") && period.payment.purchase && onPay) {
    return <PayNowButton onClick={() => onPay(period)} label={`Pay now for ${period.label}`} testId="period-pay-now" />;
  }
  if (status === "PENDING") {
    return (
      <StatusPill tone="pending" testId="period-status-pending">
        <Clock className="size-3" aria-hidden="true" />
        Pending approval
      </StatusPill>
    );
  }
  if (status === "PAID") {
    return (
      <StatusPill tone="paid" testId="period-status-paid">
        <Check className="size-3" aria-hidden="true" />
        Paid
      </StatusPill>
    );
  }
  return null;
}

function FolderToggle({
  id,
  open,
  onToggle,
  label,
  level,
  testId,
}: {
  id: string;
  open: boolean;
  onToggle: (id: string) => void;
  label: string;
  level: "period" | "module";
  testId: string;
}) {
  const FolderIcon = open ? FolderOpen : Folder;
  return (
    <button
      type="button"
      aria-expanded={open}
      aria-controls={`${id}-children`}
      onClick={() => onToggle(id)}
      data-testid={testId}
      className={cn(
        "flex min-w-0 flex-1 items-center gap-2 py-3 text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
        level === "period" ? "text-base font-semibold" : "text-sm font-medium"
      )}
    >
      {open ? (
        <ChevronDown className="size-4 shrink-0" aria-hidden="true" />
      ) : (
        <ChevronRight className="size-4 shrink-0" aria-hidden="true" />
      )}
      <FolderIcon className="size-5 shrink-0 text-blue-600" aria-hidden="true" />
      <span className="min-w-0 break-words text-foreground">{label}</span>
    </button>
  );
}

function PeriodFolder({
  period,
  timeZone,
  expanded,
  onToggle,
  launchingId,
  onLaunch,
  onPayPeriod,
  onPayLesson,
}: {
  period: PeriodNode;
  timeZone: string | null;
  expanded: ReadonlySet<string>;
  onToggle: (id: string) => void;
  launchingId: string | null;
  onLaunch: (lesson: LessonNode) => void;
  onPayPeriod: ((period: PeriodNode) => void) | null;
  onPayLesson: ((lesson: LessonNode) => void) | null;
}) {
  const id = periodId(period);
  const open = expanded.has(id);
  const label = period.key === null ? "Course content" : period.label;
  return (
    <li data-testid="content-period" data-period-key={period.key ?? ""} data-payment-status={period.payment.status}>
      <div className="flex items-center justify-between gap-2 px-4 sm:px-6">
        <FolderToggle id={id} open={open} onToggle={onToggle} label={label} level="period" testId="content-period-toggle" />
        <PeriodBadge period={period} onPay={onPayPeriod} />
      </div>
      <div id={`${id}-children`} hidden={!open}>
        {open ? (
          period.modules.length === 0 ? (
            <p className="px-10 pb-3 text-sm text-muted-foreground">Nothing in this folder yet.</p>
          ) : (
            <ul aria-label={`${label} modules`}>
              {period.modules.map((module) => (
                <ModuleFolder
                  key={moduleId(period, module)}
                  id={moduleId(period, module)}
                  module={module}
                  timeZone={timeZone}
                  expanded={expanded}
                  onToggle={onToggle}
                  launchingId={launchingId}
                  onLaunch={onLaunch}
                  onPayLesson={onPayLesson}
                />
              ))}
            </ul>
          )
        ) : null}
      </div>
    </li>
  );
}

function ModuleFolder({
  id,
  module,
  timeZone,
  expanded,
  onToggle,
  launchingId,
  onLaunch,
  onPayLesson,
}: {
  id: string;
  module: ModuleNode;
  timeZone: string | null;
  expanded: ReadonlySet<string>;
  onToggle: (id: string) => void;
  launchingId: string | null;
  onLaunch: (lesson: LessonNode) => void;
  onPayLesson: ((lesson: LessonNode) => void) | null;
}) {
  const open = expanded.has(id);
  return (
    <li data-testid="content-module" data-module-id={module.moduleId}>
      <div className="flex items-center px-4 pl-8 sm:px-6 sm:pl-10">
        <FolderToggle id={id} open={open} onToggle={onToggle} label={module.title} level="module" testId="content-module-toggle" />
      </div>
      <div id={`${id}-children`} hidden={!open}>
        {open ? (
          module.lessons.length === 0 ? (
            <p className="px-14 pb-3 text-sm text-muted-foreground">No lessons in this module yet.</p>
          ) : (
            <ul aria-label={`Lessons in ${module.title}`}>
              {module.lessons.map((lesson) => (
                <LessonRow
                  key={lesson.lessonId}
                  lesson={lesson}
                  timeZone={timeZone}
                  launching={launchingId === lesson.lessonId}
                  disabled={launchingId !== null}
                  onLaunch={onLaunch}
                  onPay={onPayLesson}
                />
              ))}
            </ul>
          )
        ) : null}
      </div>
    </li>
  );
}

export function LessonSchedule({ lesson, timeZone }: { lesson: LessonNode; timeZone: string | null }) {
  if (!lesson.startsAt && !lesson.endsAt) return null;
  return (
    <span className="flex flex-wrap gap-x-4 gap-y-0.5 text-xs font-medium">
      {lesson.startsAt ? (
        <span className="inline-flex items-center gap-1 text-green-700 dark:text-green-400">
          <CalendarClock className="size-3.5" aria-hidden="true" />
          Starts {formatLessonDateTime(lesson.startsAt, timeZone)}
        </span>
      ) : null}
      {lesson.endsAt ? (
        <span className="inline-flex items-center gap-1 text-red-600 dark:text-red-400">
          <Timer className="size-3.5" aria-hidden="true" />
          Expires {formatLessonDateTime(lesson.endsAt, timeZone)}
        </span>
      ) : null}
    </span>
  );
}

function LessonRow({
  lesson,
  timeZone,
  launching,
  disabled,
  onLaunch,
  onPay,
}: {
  lesson: LessonNode;
  timeZone: string | null;
  launching: boolean;
  disabled: boolean;
  onLaunch: (lesson: LessonNode) => void;
  onPay: ((lesson: LessonNode) => void) | null;
}) {
  const allowed = lesson.access.status === "ALLOWED";
  const canPay = lesson.access.requiredPurchase !== null && onPay !== null;

  const body = (
    <>
      {lesson.viewed ? (
        <Check className="size-5 shrink-0 rounded-full bg-green-600 p-0.5 text-white" aria-hidden="true" />
      ) : (
        <Circle className="size-5 shrink-0 text-muted-foreground" aria-hidden="true" />
      )}
      <ResourceIcon resourceType={lesson.resourceType} />
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="break-words text-sm text-foreground">
          {lesson.title}
          {lesson.lessonLabel ? <span className="text-muted-foreground"> | {lesson.lessonLabel}</span> : null}
        </span>
        <LessonSchedule lesson={lesson} timeZone={timeZone} />
        <span className="sr-only">
          {resourceLabel(lesson.resourceType)}. {lesson.viewed ? "Viewed" : "Not viewed"}. {accessStatusText(lesson)}.
        </span>
      </span>
    </>
  );

  return (
    <li
      className={cn("flex items-center justify-between gap-3 px-4 py-2 pl-12 sm:px-6 sm:pl-16", allowed && "hover:bg-muted/40")}
      data-testid="content-lesson"
      data-lesson-id={lesson.lessonId}
      data-access-status={lesson.access.status}
      data-viewed={lesson.viewed ? "true" : "false"}
    >
      {allowed ? (
        <button
          type="button"
          onClick={() => onLaunch(lesson)}
          disabled={disabled}
          aria-busy={launching}
          data-testid="content-lesson-open"
          className="flex min-w-0 flex-1 items-center gap-3 text-left outline-none focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-wait"
        >
          {body}
        </button>
      ) : (
        <div className="flex min-w-0 flex-1 items-center gap-3">{body}</div>
      )}
      <LessonAccessAction
        lesson={lesson}
        launching={launching}
        onPay={canPay && onPay ? () => onPay(lesson) : null}
      />
    </li>
  );
}
