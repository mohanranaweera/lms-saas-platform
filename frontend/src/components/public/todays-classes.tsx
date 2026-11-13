"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { AlertCircle, ArrowRight, BookOpen, CalendarClock, Clock, RotateCw, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ScheduleStatusBadge } from "@/components/calendar/status-badge";
import { usePublicTodayClasses, type PublicTodayOccurrence } from "@/lib/api/calendar";
import { occurrenceStatus, useScheduleNow, useServerClock, type ScheduleStatus } from "@/lib/calendar/status";
import { formatLongDate, formatTime, localDateOf, safeTimeZone, timeZoneLabel } from "@/lib/calendar/time";
import { cn } from "@/lib/utils";
import { useI18n, useT, type TranslateFn } from "@/i18n/i18n-provider";
import { SOURCE_LOCALE } from "@/i18n/locales";

/**
 * Public home "Today's Classes" (CAL-01, docs/specs/class-calendar.md §6.6/§8).
 * Anonymous allowlisted projection of today's public classes in the tenant's
 * zone. Statuses are schedule-based ("Live now" = inside the scheduled time
 * window) and recomputed on the server-offset clock by one shared
 * next-boundary timer; the list refetches every 5 minutes, when the tab
 * becomes visible again and at tenant-local midnight. Hidden entirely when the
 * server reports `enabled=false`. Nothing here grants access or joins a class.
 */
export function TodaysClasses() {
  const query = usePublicTodayClasses();
  const data = query.data;
  const timeZone = safeTimeZone(data?.timezone);
  const offset = useServerClock(data?.serverNow, data?.receivedAt);
  const occurrences = useMemo(() => data?.occurrences ?? [], [data]);
  const nowMs = useScheduleNow({ offsetMs: offset, events: occurrences, timeZone: data ? timeZone : null });
  const t = useT();

  // Tenant-local midnight passed: today's list is now yesterday's → refetch once for the new date.
  const currentDate = nowMs != null && data ? localDateOf(nowMs, timeZone) : null;
  const { refetch } = query;
  const dataDate = data?.date ?? null;
  useEffect(() => {
    // Keyed on the date values only, so a refetch that still returns the old date can't loop.
    if (currentDate && dataDate && currentDate !== dataDate) void refetch();
  }, [currentDate, dataDate, refetch]);

  if (query.isPending) {
    return (
      <Shell>
        <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3" role="status" aria-label={t("public.todaysClasses.loading")}>
          {[0, 1, 2].map((key) => (
            <Skeleton key={key} className={cn("h-64 rounded-xl", ["", "hidden sm:block", "hidden lg:block"][key])} />
          ))}
        </div>
      </Shell>
    );
  }

  if (query.isError) {
    return (
      <Shell>
        <div role="alert" className="flex flex-col items-start gap-3 rounded-xl border border-destructive/30 bg-destructive/5 p-4 sm:flex-row sm:items-center" data-testid="todays-classes-error">
          <AlertCircle className="size-5 shrink-0 text-destructive" aria-hidden="true" />
          <p className="flex-1 text-sm">{t("public.todaysClasses.error")}</p>
          <Button type="button" variant="outline" size="sm" onClick={() => void query.refetch()}>
            <RotateCw aria-hidden="true" />
            {t("common.actions.retry")}
          </Button>
        </div>
      </Shell>
    );
  }

  if (!data?.enabled) return null;

  return <TodaysClassesList occurrences={occurrences} date={data.date} timeZone={timeZone} nowMs={nowMs} />;
}

function Shell({ children, subtitle, summary }: { children: React.ReactNode; subtitle?: string; summary?: string }) {
  const t = useT();
  return (
    <section className="mx-auto w-full max-w-7xl px-4 py-14 sm:px-6 lg:px-8" aria-labelledby="todays-classes-heading" data-testid="todays-classes">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <p className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-widest text-primary">
            <CalendarClock className="size-4" aria-hidden="true" />
            {t("public.todaysClasses.eyebrow")}
          </p>
          <h2 id="todays-classes-heading" className="mt-2 text-2xl font-bold tracking-tight break-words sm:text-3xl">
            {t("public.todaysClasses.heading")}
          </h2>
          {subtitle ? (
            <p className="mt-2 text-muted-foreground" data-testid="todays-classes-date">
              {subtitle}
            </p>
          ) : null}
        </div>
        {summary ? (
          <p className="text-sm font-medium text-foreground" aria-live="polite" data-testid="todays-classes-summary">
            {summary}
          </p>
        ) : null}
      </div>
      <div className="mt-8">{children}</div>
    </section>
  );
}

const GROUPS: {
  status: Exclude<ScheduleStatus, "CANCELLED">;
  title: (t: TranslateFn) => string;
  summary: (t: TranslateFn, count: number) => string;
}[] = [
  {
    status: "LIVE",
    title: (t) => t("common.scheduleStatus.live"),
    summary: (t, count) => t("public.todaysClasses.summaryLive", { count }),
  },
  {
    status: "SCHEDULED",
    title: (t) => t("common.scheduleStatus.scheduled"),
    summary: (t, count) => t("public.todaysClasses.summaryScheduled", { count }),
  },
  {
    status: "OVER",
    title: (t) => t("common.scheduleStatus.over"),
    summary: (t, count) => t("public.todaysClasses.summaryOver", { count }),
  },
];

function TodaysClassesList({
  occurrences,
  date,
  timeZone,
  nowMs,
}: {
  occurrences: PublicTodayOccurrence[];
  date: string;
  timeZone: string;
  nowMs: number | null;
}) {
  const t = useT();
  const { locale } = useI18n();
  const intlLocale = locale === SOURCE_LOCALE ? undefined : locale;
  const groups = GROUPS.map((group) => {
    const items = occurrences.filter((occurrence) => occurrenceStatus(occurrence, nowMs) === group.status);
    items.sort((a, b) =>
      group.status === "OVER"
        ? Date.parse(b.end) - Date.parse(a.end) || a.courseName.localeCompare(b.courseName)
        : Date.parse(a.start) - Date.parse(b.start) || Date.parse(a.end) - Date.parse(b.end) || a.courseName.localeCompare(b.courseName)
    );
    return { ...group, items };
  });
  const summary = groups
    .filter((group) => group.items.length > 0)
    .map((group) => group.summary(t, group.items.length))
    .join(" · ");
  const subtitle = `${formatLongDate(date, intlLocale)} · ${timeZoneLabel(timeZone)}`;

  if (occurrences.length === 0) {
    return (
      <Shell subtitle={subtitle}>
        <div className="rounded-xl border border-dashed border-border px-6 py-10 text-center" data-testid="todays-classes-empty">
          <p className="font-medium">{t("public.todaysClasses.empty")}</p>
          <p className="mt-1 text-sm text-muted-foreground">
            <Link href="/courses" className="font-semibold text-primary hover:underline">
              {t("public.todaysClasses.browseCourses")}
            </Link>{" "}
            {t("public.todaysClasses.browseSuffix")}
          </p>
        </div>
      </Shell>
    );
  }

  return (
    <Shell subtitle={subtitle} summary={summary}>
      <div className="flex flex-col gap-8">
        {groups
          .filter((group) => group.items.length > 0)
          .map((group) => (
            <div key={group.status} className="flex flex-col gap-4" data-testid="todays-classes-group" data-status={group.status}>
              <h3 className="text-lg font-semibold">
                {group.title(t)} <span className="text-sm font-normal text-muted-foreground">({group.items.length})</span>
              </h3>
              <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
                {group.items.map((occurrence) => (
                  <li key={occurrence.id} className="min-w-0">
                    <ClassCard occurrence={occurrence} status={group.status} timeZone={timeZone} />
                  </li>
                ))}
              </ul>
            </div>
          ))}
      </div>
    </Shell>
  );
}

function ClassCard({
  occurrence,
  status,
  timeZone,
}: {
  occurrence: PublicTodayOccurrence;
  status: ScheduleStatus;
  timeZone: string;
}) {
  const [coverFailed, setCoverFailed] = useState(false);
  const [photoFailed, setPhotoFailed] = useState(false);
  const over = status === "OVER";
  const t = useT();
  const { locale } = useI18n();
  const timeLocale = locale === SOURCE_LOCALE ? undefined : locale;
  const hint = t("common.scheduleStatus.basedOnSchedule");
  return (
    <article
      className={cn(
        "flex h-full min-w-0 flex-row overflow-hidden rounded-xl sm:flex-col border border-border bg-card text-card-foreground shadow-sm transition-shadow hover:shadow-md",
        over && "bg-muted/40 opacity-80"
      )}
      data-testid="todays-class-card"
      data-occurrence-id={occurrence.id}
      data-status={status}
    >
      {/* Below sm: compact row card (thumbnail left) so a busy day stays scannable. */}
      <div className="relative w-24 shrink-0 overflow-hidden bg-(--tenant-primary-soft) sm:aspect-[16/7] sm:w-auto">
        {occurrence.coverUrl && !coverFailed ? (
          // eslint-disable-next-line @next/next/no-img-element -- signed course cover URL
          <img
            src={occurrence.coverUrl}
            alt=""
            className={cn("absolute inset-0 size-full object-cover", over && "grayscale-[40%]")}
            loading="lazy"
            onError={() => setCoverFailed(true)}
          />
        ) : (
          <span className="absolute inset-0 flex items-center justify-center text-primary" aria-hidden="true">
            <BookOpen className="size-7 opacity-60 sm:size-10" />
          </span>
        )}
        <span className="absolute top-3 left-3 hidden sm:block" title={status === "LIVE" ? hint : undefined}>
          <ScheduleStatusBadge status={status} className="bg-card shadow-sm" />
        </span>
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-2 p-4 sm:gap-3 sm:p-5">
        <span className="sm:hidden" title={status === "LIVE" ? hint : undefined}>
          <ScheduleStatusBadge status={status} />
        </span>
        <h4 className="line-clamp-2 text-base font-semibold break-words sm:text-lg" title={occurrence.courseName}>
          {occurrence.courseName}
        </h4>
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Clock className="size-4 shrink-0 text-primary" aria-hidden="true" />
          <span className="tabular-nums" data-testid="todays-class-time">
            {formatTime(Date.parse(occurrence.start), timeZone, timeLocale)} – {formatTime(Date.parse(occurrence.end), timeZone, timeLocale)}
          </span>
        </p>
        {occurrence.teacherName ? (
          <p className="flex min-w-0 items-center gap-2 text-sm">
            {occurrence.teacherPhotoUrl && !photoFailed ? (
              // eslint-disable-next-line @next/next/no-img-element -- signed teacher photo URL
              <img
                src={occurrence.teacherPhotoUrl}
                alt=""
                className="size-7 shrink-0 rounded-full object-cover"
                loading="lazy"
                onError={() => setPhotoFailed(true)}
              />
            ) : (
              <span className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary" aria-hidden="true">
                <UserRound className="size-4" />
              </span>
            )}
            <span className="truncate">{occurrence.teacherName}</span>
          </p>
        ) : null}
        <Link
          href={`/courses/${encodeURIComponent(occurrence.courseSlug)}`}
          className="mt-auto inline-flex items-center justify-end gap-1 border-t border-border pt-3 text-sm sm:pt-4 font-semibold text-primary hover:underline"
        >
          {t("public.todaysClasses.viewCourse")}<span className="sr-only"> {occurrence.courseName}</span>
          <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </div>
    </article>
  );
}
