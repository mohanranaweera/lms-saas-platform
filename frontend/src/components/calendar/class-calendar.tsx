"use client";

import { useCallback, useMemo, useState, useSyncExternalStore } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { CalendarDays, CalendarRange, ChevronLeft, ChevronRight, Download, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { CalendarDialog } from "./calendar-dialog";
import { AgendaView, fullDate, MonthView, OccurrenceChip, TimeGridView } from "./calendar-views";
import { OccurrenceDetailsDialog, type CalendarPortal } from "./occurrence-details-dialog";
import { isApiClientError } from "@/lib/api/error";
import { reportDownloadErrorMessage } from "@/lib/api/reports";
import {
  useCalendarCourses,
  useCalendarOccurrences,
  useDownloadTimetable,
  type CalendarOccurrence,
  type CalendarView,
} from "@/lib/api/calendar";
import { occurrenceStatus, useScheduleNow, useServerClock } from "@/lib/calendar/status";
import {
  addDays,
  addMonths,
  datesBetween,
  formatLocalDate,
  isLocalDate,
  localDateOf,
  safeTimeZone,
  sameMonth,
  startOfMonth,
  startOfWeek,
  timeZoneLabel,
  type LocalDate,
} from "@/lib/calendar/time";
import { cn } from "@/lib/utils";

/**
 * CAL-01 "Calendar & Timetable" (docs/specs/class-calendar.md §8), shared by
 * the Tenant Admin, Teacher and Student portals. Which courses appear is
 * decided by the server from the session (§5); the course filter only asks
 * the server to narrow it. URL state: `?view=month|week|day|list&date=YYYY-MM-DD&course=<id>`.
 * "Today" and every date/time shown come from the tenant zone + server
 * clock in the response — never the browser's zone.
 */

type ViewMode = "month" | "week" | "day" | "list";
const VIEW_MODES: { value: ViewMode; label: string }[] = [
  { value: "month", label: "Month" },
  { value: "week", label: "Week" },
  { value: "day", label: "Day" },
  { value: "list", label: "List" },
];

const ALL_COURSES = "all";

function subscribeMobile(callback: () => void) {
  const media = window.matchMedia("(max-width: 639px)");
  media.addEventListener("change", callback);
  return () => media.removeEventListener("change", callback);
}

function useIsMobile(): boolean {
  return useSyncExternalStore(subscribeMobile, () => window.matchMedia("(max-width: 639px)").matches, () => false);
}

function rangeFor(view: ViewMode, anchor: LocalDate): { from: LocalDate; to: LocalDate } {
  switch (view) {
    case "week": {
      const from = startOfWeek(anchor);
      return { from, to: addDays(from, 7) };
    }
    case "day":
      return { from: anchor, to: addDays(anchor, 1) };
    default: {
      const from = startOfWeek(startOfMonth(anchor));
      return { from, to: addDays(from, 42) };
    }
  }
}

function shift(view: ViewMode, anchor: LocalDate, direction: 1 | -1): LocalDate {
  switch (view) {
    case "week":
      return addDays(anchor, 7 * direction);
    case "day":
      return addDays(anchor, direction);
    default:
      return addMonths(anchor, direction);
  }
}

function periodHeading(view: ViewMode, anchor: LocalDate, from: LocalDate): string {
  if (view === "day") return fullDate(anchor);
  if (view === "week") {
    const last = addDays(from, 6);
    if (sameMonth(from, last)) {
      return `${formatLocalDate(from, { day: "numeric" })} – ${formatLocalDate(last, { day: "numeric", month: "long", year: "numeric" })}`;
    }
    const sameYear = from.slice(0, 4) === last.slice(0, 4);
    return `${formatLocalDate(from, sameYear ? { day: "numeric", month: "short" } : { day: "numeric", month: "short", year: "numeric" })} – ${formatLocalDate(last, { day: "numeric", month: "short", year: "numeric" })}`;
  }
  return formatLocalDate(anchor, { month: "long", year: "numeric" });
}

const UNIT: Record<ViewMode, string> = { month: "month", list: "month", week: "week", day: "day" };
const PDF_VIEW: Record<ViewMode, CalendarView> = { month: "MONTH", list: "MONTH", week: "WEEK", day: "DAY" };

export function ClassCalendar({ portal }: { portal: CalendarPortal }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const isMobile = useIsMobile();
  const toast = useAdminToast();

  const viewParam = searchParams.get("view");
  const view: ViewMode = VIEW_MODES.some((mode) => mode.value === viewParam)
    ? (viewParam as ViewMode)
    : isMobile
      ? "list"
      : "month";
  const dateParam = searchParams.get("date");
  const courseParam = searchParams.get("course") || null;

  // Provisional anchor until the first response tells us the tenant's "today".
  const [provisionalToday] = useState(() => new Date().toISOString().slice(0, 10));
  const [knownToday, setKnownToday] = useState<LocalDate | null>(null);
  const anchor: LocalDate = isLocalDate(dateParam) ? dateParam : (knownToday ?? provisionalToday);
  const { from, to } = rangeFor(view, anchor);

  const courses = useCalendarCourses();
  const query = useCalendarOccurrences({ from, to, courseId: courseParam });
  const data = query.data;
  const timeZone = safeTimeZone(data?.timezone);

  const offset = useServerClock(data?.serverNow, data?.receivedAt);
  const occurrences = useMemo(() => data?.occurrences ?? [], [data]);
  const nowMs = useScheduleNow({
    offsetMs: offset,
    events: occurrences,
    timeZone: data ? timeZone : null,
    minuteTicks: view === "week" || view === "day",
  });
  const serverNowMs = data ? Date.parse(data.serverNow) : null;
  const effectiveNow = nowMs ?? serverNowMs;
  const today = data && effectiveNow != null ? localDateOf(effectiveNow, timeZone) : null;
  if (today && today !== knownToday) setKnownToday(today);

  const statusOf = useCallback((occurrence: CalendarOccurrence) => occurrenceStatus(occurrence, nowMs), [nowMs]);

  const setParams = (updates: Record<string, string | null>) => {
    const params = new URLSearchParams(searchParams.toString());
    for (const [key, value] of Object.entries(updates)) {
      if (value == null) params.delete(key);
      else params.set(key, value);
    }
    const qs = params.toString();
    router.replace(qs ? `${pathname}?${qs}` : pathname, { scroll: false });
  };

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [dayListDate, setDayListDate] = useState<LocalDate | null>(null);
  const selected = selectedId ? (occurrences.find((occurrence) => occurrence.id === selectedId) ?? null) : null;

  const download = useDownloadTimetable();
  const onDownload = () => {
    download.mutate(
      { from, to, courseId: courseParam, view: PDF_VIEW[view] },
      {
        onSuccess: (filename) => toast.show({ kind: "success", text: `Timetable downloaded (${filename}).` }),
        onError: (error) =>
          toast.show({
            kind: "error",
            text: `Could not download the timetable: ${reportDownloadErrorMessage(error, "Choose a shorter period or a single course.")}`,
          }),
      }
    );
  };

  const courseItems = useMemo(() => {
    const items: Record<string, string> = { [ALL_COURSES]: "All courses" };
    for (const course of courses.data ?? []) items[course.id] = course.name;
    return items;
  }, [courses.data]);

  const heading = periodHeading(view, anchor, from);
  const unit = UNIT[view];
  const loadingNext = query.isFetching && query.isPlaceholderData;

  // Occurrences actually visible in the current view (list = this month only).
  const visible = view === "list" ? occurrences.filter((occurrence) => sameMonth(occurrence.localDate, anchor)) : occurrences;
  const dayList = dayListDate ? occurrences.filter((occurrence) => occurrence.localDate === dayListDate) : [];

  const courseFilterError = isApiClientError(query.error) && query.error.status === 404 && courseParam != null;

  return (
    <div className="flex min-w-0 flex-col gap-4">
      <div className="flex items-center gap-3">
        <span className="flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground" aria-hidden="true">
          <CalendarDays className="size-5" />
        </span>
        <div className="min-w-0">
          <h1 className="text-xl font-semibold text-foreground">Calendar &amp; Timetable</h1>
          <p className="text-sm text-muted-foreground">
            {portal === "student"
              ? "Class times for the courses you have access to."
              : portal === "teacher"
                ? "Class times for the courses you teach."
                : "Class times for every active course in your institute."}
            {data ? ` Times are in ${timeZoneLabel(timeZone)}.` : null}
          </p>
        </div>
      </div>

      <section className="flex min-w-0 flex-col gap-4 rounded-xl border border-border bg-card p-3 sm:p-4" aria-labelledby="calendar-period-heading">
        <div className="flex flex-col gap-3 lg:flex-row lg:flex-wrap lg:items-center lg:justify-between">
          <div className="flex flex-wrap items-center gap-2">
            <h2 id="calendar-period-heading" className="min-w-0 text-lg font-semibold text-foreground sm:min-w-44" aria-live="polite" data-testid="calendar-period-heading">
              {heading}
            </h2>
            <Button
              type="button"
              variant="secondary"
              onClick={() => setParams({ date: today ?? null })}
              data-testid="calendar-today"
            >
              <CalendarRange aria-hidden="true" />
              Today
            </Button>
            <Button type="button" variant="ghost" size="icon" aria-label={`Previous ${unit}`} onClick={() => setParams({ date: shift(view, anchor, -1) })}>
              <ChevronLeft aria-hidden="true" />
            </Button>
            <Button type="button" variant="ghost" size="icon" aria-label={`Next ${unit}`} onClick={() => setParams({ date: shift(view, anchor, 1) })}>
              <ChevronRight aria-hidden="true" />
            </Button>
            {loadingNext ? (
              <span role="status" className="flex items-center gap-1 text-xs text-muted-foreground" data-testid="calendar-updating">
                <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />
                Updating…
              </span>
            ) : null}
          </div>

          <div className="flex flex-wrap items-end gap-2">
            <div className="flex min-w-0 flex-col gap-1">
              <label htmlFor="calendar-course-filter" className="text-xs font-medium text-muted-foreground">
                Course
              </label>
              <Select
                items={courseItems}
                value={courseParam ?? ALL_COURSES}
                onValueChange={(value) => setParams({ course: !value || value === ALL_COURSES ? null : String(value) })}
              >
                <SelectTrigger id="calendar-course-filter" className="h-8 w-full min-w-48 sm:w-60" data-testid="calendar-course-filter">
                  <SelectValue placeholder="All courses" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ALL_COURSES}>All courses</SelectItem>
                  {(courses.data ?? []).map((course) => (
                    <SelectItem key={course.id} value={course.id}>
                      {course.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div role="group" aria-label="Calendar view" className="flex rounded-lg border border-border bg-muted p-0.5" data-testid="calendar-view-switch">
              {VIEW_MODES.map((mode) => (
                <button
                  key={mode.value}
                  type="button"
                  aria-pressed={view === mode.value}
                  onClick={() => setParams({ view: mode.value })}
                  className={cn(
                    "h-7 rounded-md px-3 text-xs font-semibold uppercase tracking-wide outline-none focus-visible:ring-3 focus-visible:ring-ring/50",
                    view === mode.value ? "bg-background text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground"
                  )}
                >
                  {mode.label}
                </button>
              ))}
            </div>
            <Button
              type="button"
              variant="outline"
              onClick={onDownload}
              disabled={download.isPending}
              aria-busy={download.isPending}
              data-testid="calendar-download"
            >
              {download.isPending ? <Loader2 className="animate-spin" aria-hidden="true" /> : <Download aria-hidden="true" />}
              {download.isPending ? "Preparing PDF…" : "Download timetable"}
            </Button>
          </div>
        </div>

        {courseFilterError ? (
          <EmptyState
            title="This course isn't in your calendar"
            description="The selected course doesn't exist or isn't one you can see. Show all of your courses instead."
            action={{ label: "Show all courses", onClick: () => setParams({ course: null }) }}
          />
        ) : (
          <QueryStateBoundary
            query={query}
            loadingLabel="Loading classes…"
            loginPath="/login"
            permissionDenied={{ dashboardHref: `/${portal}/dashboard` }}
          >
            {() => (
              <div className={cn("flex min-w-0 flex-col gap-3 transition-opacity", loadingNext && "opacity-60")} aria-busy={loadingNext}>
                {visible.length === 0 ? (
                  <p role="status" className="rounded-lg border border-dashed border-border px-4 py-3 text-center text-sm text-muted-foreground" data-testid="calendar-empty">
                    No classes in this period.
                    {courseParam ? " Try “All courses” or another period." : " Try another period."}
                  </p>
                ) : null}
                {view === "month" ? (
                  <MonthView
                    occurrences={occurrences}
                    statusOf={statusOf}
                    timeZone={timeZone}
                    today={today}
                    onSelect={(occurrence) => setSelectedId(occurrence.id)}
                    from={from}
                    to={to}
                    anchor={anchor}
                    onShowDay={setDayListDate}
                  />
                ) : view === "list" ? (
                  <AgendaView
                    occurrences={visible}
                    statusOf={statusOf}
                    timeZone={timeZone}
                    today={today}
                    onSelect={(occurrence) => setSelectedId(occurrence.id)}
                  />
                ) : (
                  <TimeGridView
                    occurrences={occurrences}
                    statusOf={statusOf}
                    timeZone={timeZone}
                    today={today}
                    onSelect={(occurrence) => setSelectedId(occurrence.id)}
                    days={datesBetween(from, to)}
                    nowMs={effectiveNow}
                    onShowDay={setDayListDate}
                  />
                )}
              </div>
            )}
          </QueryStateBoundary>
        )}
      </section>

      <CalendarDialog
        open={dayListDate != null}
        onOpenChange={(open) => {
          if (!open) setDayListDate(null);
        }}
        title={dayListDate ? `Classes on ${fullDate(dayListDate)}` : "Classes"}
        icon={<CalendarDays className="size-5 shrink-0 text-primary" aria-hidden="true" />}
        testId="calendar-day-list"
      >
        <ul className="flex flex-col gap-1.5">
          {dayList.map((occurrence) => (
            <li key={occurrence.id}>
              <OccurrenceChip
                occurrence={occurrence}
                status={statusOf(occurrence)}
                timeZone={timeZone}
                className="py-1.5 text-sm"
                onSelect={(picked) => {
                  setDayListDate(null);
                  setSelectedId(picked.id);
                }}
              />
            </li>
          ))}
        </ul>
      </CalendarDialog>

      <OccurrenceDetailsDialog
        occurrence={selected}
        status={selected ? statusOf(selected) : "SCHEDULED"}
        timeZone={timeZone}
        portal={portal}
        onClose={() => setSelectedId(null)}
      />

      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}
