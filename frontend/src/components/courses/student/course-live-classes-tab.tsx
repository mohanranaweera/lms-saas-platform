"use client";

import type { ReactNode } from "react";
import { Video } from "lucide-react";
import { Button } from "@/components/ui/button";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { isApiClientError } from "@/lib/api/error";
import { useCourseLiveClasses, type LessonNode, type LiveClassNode } from "@/lib/api/klass-student";
import { LessonAccessAction } from "./lesson-access-action";
import { dayGroup, formatTimeOfDay, resourceLabel } from "./student-format";

/**
 * "Upcoming live classes" (CM-S02): the course's live lessons from the same
 * server evaluator as the content tree, grouped by day in the course
 * timezone. Join launches the lesson (server re-checks access); Pay now opens
 * the composer with the lesson's required purchase.
 */
export function CourseLiveClassesTab({
  courseId,
  timeZone,
  launchingId,
  onLaunch,
  onPayLesson,
  notEnrolled,
}: {
  courseId: string;
  timeZone: string | null;
  launchingId: string | null;
  onLaunch: (lesson: LessonNode) => void;
  onPayLesson: ((lesson: LessonNode) => void) | null;
  notEnrolled: ReactNode;
}) {
  const query = useCourseLiveClasses(courseId);

  if (query.status === "pending") return <LoadingState label="Loading live classes…" />;
  if (query.status === "error") {
    if (isApiClientError(query.error) && (query.error.status === 404 || query.error.status === 403)) {
      return <>{notEnrolled}</>;
    }
    return (
      <ErrorState
        message="We couldn't load this course's live classes."
        code={isApiClientError(query.error) ? query.error.code : undefined}
        onRetry={() => query.refetch()}
      />
    );
  }

  const classes = query.data;
  const groups: { key: string; label: string; items: LiveClassNode[] }[] = [];
  const unscheduled: LiveClassNode[] = [];
  for (const item of classes) {
    if (!item.startsAt) {
      unscheduled.push(item);
      continue;
    }
    const day = dayGroup(item.startsAt, timeZone);
    const group = groups.find((existing) => existing.key === day.key);
    if (group) group.items.push(item);
    else groups.push({ key: day.key, label: day.label, items: [item] });
  }
  if (unscheduled.length > 0) groups.push({ key: "unscheduled", label: "Time not set", items: unscheduled });

  return (
    <section
      aria-labelledby="live-classes-heading"
      className="overflow-hidden rounded-xl border border-border bg-card shadow-sm"
      data-testid="live-classes-panel"
    >
      <div className="flex items-center justify-between gap-2 border-b border-border px-4 py-4 sm:px-6">
        <h2 id="live-classes-heading" className="text-base font-semibold text-foreground">
          Upcoming live classes
        </h2>
        <span
          className="rounded-full bg-blue-50 px-3 py-1 text-xs font-semibold text-blue-700 dark:bg-blue-950 dark:text-blue-200"
          data-testid="live-classes-count"
        >
          {classes.length} {classes.length === 1 ? "class" : "classes"}
        </span>
      </div>

      {classes.length === 0 ? (
        <p className="px-6 py-10 text-center text-sm text-muted-foreground" role="status" data-testid="live-classes-empty">
          No upcoming live classes. When your teacher schedules one, it will appear here.
        </p>
      ) : (
        <ul className="divide-y divide-border" aria-label="Upcoming live classes by day">
          {groups.map((group) => (
            <li key={group.key}>
              <h3 className="px-4 pt-4 text-xs font-semibold tracking-wide text-muted-foreground uppercase sm:px-6">
                {group.label}
              </h3>
              <ul>
                {group.items.map((item) => (
                  <LiveClassRow
                    key={item.lessonId}
                    item={item}
                    timeZone={timeZone}
                    launching={launchingId === item.lessonId}
                    disabled={launchingId !== null}
                    onLaunch={onLaunch}
                    onPay={onPayLesson}
                  />
                ))}
              </ul>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function LiveClassRow({
  item,
  timeZone,
  launching,
  disabled,
  onLaunch,
  onPay,
}: {
  item: LiveClassNode;
  timeZone: string | null;
  launching: boolean;
  disabled: boolean;
  onLaunch: (lesson: LessonNode) => void;
  onPay: ((lesson: LessonNode) => void) | null;
}) {
  const allowed = item.access.status === "ALLOWED";
  return (
    <div
      className="flex flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:px-6"
      data-testid="live-class-row"
      data-lesson-id={item.lessonId}
      data-access-status={item.access.status}
    >
      <div className="flex gap-4">
        <div className="flex w-20 shrink-0 flex-col">
          <span className="text-sm font-semibold text-foreground">
            {item.startsAt ? formatTimeOfDay(item.startsAt, timeZone) : "—"}
          </span>
          {item.endsAt ? (
            <span className="text-xs text-muted-foreground">
              <span className="sr-only">until </span>
              {formatTimeOfDay(item.endsAt, timeZone)}
            </span>
          ) : null}
        </div>
        <div className="flex min-w-0 flex-col gap-1">
          <span className="text-sm font-medium text-foreground">
            {item.title}
            {item.lessonLabel ? <span className="text-muted-foreground"> | {item.lessonLabel}</span> : null}
          </span>
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
            <span className="inline-flex items-center gap-1">
              <Video className="size-3.5" aria-hidden="true" />
              {resourceLabel(item.resourceType)}
            </span>
            <span>{item.moduleTitle}</span>
          </span>
        </div>
      </div>
      <div className="flex justify-end">
        {allowed ? (
          <Button
            type="button"
            size="sm"
            onClick={() => onLaunch(item)}
            disabled={disabled}
            aria-busy={launching}
            aria-label={`Join ${item.title}`}
            data-testid="live-class-join"
            className="bg-blue-600 text-white hover:bg-blue-700"
          >
            {launching ? "Opening…" : "Join"}
          </Button>
        ) : (
          <LessonAccessAction
            lesson={item}
            launching={launching}
            onPay={item.access.requiredPurchase !== null && onPay ? () => onPay(item) : null}
          />
        )}
      </div>
    </div>
  );
}
