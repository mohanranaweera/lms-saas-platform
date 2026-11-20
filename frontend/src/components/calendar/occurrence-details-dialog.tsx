"use client";

import Link from "next/link";
import { CalendarDays, ExternalLink } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { CalendarDialog } from "./calendar-dialog";
import { ScheduleStatusBadge, SCHEDULE_BASED_HINT } from "./status-badge";
import type { CalendarOccurrence } from "@/lib/api/calendar";
import type { ScheduleStatus } from "@/lib/calendar/status";
import { formatLongDate, formatTime, timeZoneLabel } from "@/lib/calendar/time";

import { useT } from "@/i18n/i18n-provider";
export type CalendarPortal = "tenant-admin" | "teacher" | "student";

/** Role-appropriate course destination. Each route re-checks access server-side. */
export function courseHref(portal: CalendarPortal, courseId: string): string {
  const id = encodeURIComponent(courseId);
  switch (portal) {
    case "tenant-admin":
      return `/tenant-admin/courses/${id}`;
    case "teacher":
      return `/teacher/courses/${id}/modules`;
    case "student":
      return `/student/courses/${id}`;
  }
}

export function OccurrenceDetailsDialog({
  occurrence,
  status,
  timeZone,
  portal,
  onClose,
}: {
  occurrence: CalendarOccurrence | null;
  status: ScheduleStatus;
  timeZone: string;
  portal: CalendarPortal;
  onClose: () => void;
}) {
  const t = useT();
  return (
    <CalendarDialog
      open={occurrence != null}
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
      title={occurrence?.courseName ?? t("calendar.occurrenceDetailsDialog.classDetails")}
      icon={<CalendarDays className="size-5 shrink-0 text-primary" aria-hidden="true" />}
      testId="occurrence-details-dialog"
      footer={
        occurrence ? (
          <>
            <Button type="button" variant="outline" onClick={onClose}>
              {t("calendar.occurrenceDetailsDialog.close")}
            </Button>
            <Link href={courseHref(portal, occurrence.courseId)} className={buttonVariants()}>
              {t("calendar.occurrenceDetailsDialog.viewCourse")}
              <ExternalLink aria-hidden="true" />
            </Link>
          </>
        ) : null
      }
    >
      {occurrence ? (
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-3" data-testid="occurrence-details">
          <dt className="text-muted-foreground">{t("calendar.occurrenceDetailsDialog.status")}</dt>
          <dd className="flex flex-col gap-1">
            <ScheduleStatusBadge status={status} />
            {status === "LIVE" || status === "SCHEDULED" ? (
              <span className="text-xs text-muted-foreground">{SCHEDULE_BASED_HINT}.</span>
            ) : null}
          </dd>
          {occurrence.sessionTitle ? (
            <>
              <dt className="text-muted-foreground">{t("calendar.occurrenceDetailsDialog.session")}</dt>
              <dd data-testid="occurrence-session-title">{occurrence.sessionTitle}</dd>
            </>
          ) : null}
          <dt className="text-muted-foreground">{t("calendar.occurrenceDetailsDialog.date")}</dt>
          <dd data-testid="occurrence-date">
            {formatLongDate(occurrence.localDate)}
          </dd>
          <dt className="text-muted-foreground">{t("calendar.occurrenceDetailsDialog.time")}</dt>
          <dd data-testid="occurrence-time" className={status === "CANCELLED" ? "line-through" : undefined}>
            {formatTime(Date.parse(occurrence.start), timeZone)} – {formatTime(Date.parse(occurrence.end), timeZone)}
          </dd>
          <dt className="text-muted-foreground">{t("calendar.occurrenceDetailsDialog.timeZone")}</dt>
          <dd>{timeZoneLabel(timeZone)}</dd>
          <dt className="text-muted-foreground">{occurrence.teacherNames.length > 1 ? t("calendar.occurrenceDetailsDialog.teachers") : t("calendar.occurrenceDetailsDialog.teacher")}</dt>
          <dd data-testid="occurrence-teachers">
            {occurrence.teacherNames.length > 0 ? occurrence.teacherNames.join(", ") : t("calendar.occurrenceDetailsDialog.notAssigned")}
          </dd>
          {occurrence.source === "SESSION" ? (
            <>
              <dt className="text-muted-foreground">{t("calendar.occurrenceDetailsDialog.type")}</dt>
              <dd>{occurrence.replacesOccurrenceId ? t("calendar.occurrenceDetailsDialog.liveSessionRescheduledClass") : t("calendar.occurrenceDetailsDialog.liveSession")}</dd>
            </>
          ) : null}
        </dl>
      ) : null}
    </CalendarDialog>
  );
}
