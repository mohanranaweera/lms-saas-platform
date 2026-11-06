"use client";

import { useEffect, useMemo, useRef } from "react";
import { Ban } from "lucide-react";
import { cn } from "@/lib/utils";
import { courseColor } from "./course-color";
import { ScheduleStatusBadge, STATUS_LABELS } from "./status-badge";
import type { CalendarOccurrence } from "@/lib/api/calendar";
import type { ScheduleStatus } from "@/lib/calendar/status";
import {
  dayOfMonth,
  datesBetween,
  formatLocalDate,
  formatLongDate,
  formatTime,
  localDateOf,
  localMinutesOf,
  sameMonth,
  type LocalDate,
} from "@/lib/calendar/time";

/**
 * CAL-01 calendar views (month grid, week/day time grid, agenda list). Pure
 * presentation over the server's occurrences; no drag/resize, nothing here
 * changes data. Every chip is a real `<button>` (keyboard operable) whose
 * accessible name carries time, course and status, so colour is never the
 * only signal.
 */

export interface ViewProps {
  occurrences: CalendarOccurrence[];
  statusOf: (occurrence: CalendarOccurrence) => ScheduleStatus;
  timeZone: string;
  today: LocalDate | null;
  onSelect: (occurrence: CalendarOccurrence) => void;
}

const WEEKDAY_SHORT = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"];

export function fullDate(date: LocalDate): string {
  return formatLongDate(date);
}

export function chipLabel(occurrence: CalendarOccurrence, status: ScheduleStatus, timeZone: string): string {
  const start = formatTime(Date.parse(occurrence.start), timeZone);
  const end = formatTime(Date.parse(occurrence.end), timeZone);
  return `${start} to ${end}, ${occurrence.courseName}, ${STATUS_LABELS[status]}`;
}

function groupByDate(occurrences: CalendarOccurrence[]): Map<LocalDate, CalendarOccurrence[]> {
  const map = new Map<LocalDate, CalendarOccurrence[]>();
  for (const occurrence of occurrences) {
    const list = map.get(occurrence.localDate) ?? [];
    list.push(occurrence);
    map.set(occurrence.localDate, list);
  }
  return map;
}

/** Month-grid / list chip: "8:00 AM Course name" on the course colour; cancelled = struck through + "Cancelled". */
export function OccurrenceChip({
  occurrence,
  status,
  timeZone,
  onSelect,
  className,
}: {
  occurrence: CalendarOccurrence;
  status: ScheduleStatus;
  timeZone: string;
  onSelect: (occurrence: CalendarOccurrence) => void;
  className?: string;
}) {
  const color = courseColor(occurrence.courseId);
  const cancelled = status === "CANCELLED";
  const time = formatTime(Date.parse(occurrence.start), timeZone);
  return (
    <button
      type="button"
      onClick={() => onSelect(occurrence)}
      aria-label={chipLabel(occurrence, status, timeZone)}
      title={`${time} ${occurrence.courseName}${cancelled ? " (Cancelled)" : ""}`}
      data-testid="calendar-chip"
      data-occurrence-id={occurrence.id}
      data-status={status}
      className={cn(
        "flex w-full min-w-0 items-center gap-1 rounded-md px-1.5 py-0.5 text-left text-xs font-medium outline-none focus-visible:ring-3 focus-visible:ring-ring/60",
        cancelled ? "border border-dashed bg-background text-muted-foreground" : "text-white shadow-xs hover:brightness-110",
        status === "OVER" && "opacity-80",
        className
      )}
      style={cancelled ? { borderColor: color } : { backgroundColor: color }}
    >
      {status === "LIVE" ? <span className="size-1.5 shrink-0 rounded-full bg-white" aria-hidden="true" /> : null}
      {cancelled ? <Ban className="size-3 shrink-0" aria-hidden="true" /> : null}
      <span className={cn("min-w-0 truncate", cancelled && "line-through")}>
        <span className="tabular-nums">{time}</span> {occurrence.courseName}
      </span>
      {cancelled ? <span className="shrink-0 no-underline">Cancelled</span> : null}
    </button>
  );
}

const MAX_CHIPS = 3;

export function MonthView({
  occurrences,
  statusOf,
  timeZone,
  today,
  onSelect,
  from,
  to,
  anchor,
  onShowDay,
}: ViewProps & { from: LocalDate; to: LocalDate; anchor: LocalDate; onShowDay: (date: LocalDate) => void }) {
  const byDate = useMemo(() => groupByDate(occurrences), [occurrences]);
  const days = datesBetween(from, to);
  const weeks: LocalDate[][] = [];
  for (let i = 0; i < days.length; i += 7) weeks.push(days.slice(i, i + 7));

  return (
    <div className="overflow-hidden rounded-xl border border-border" data-testid="calendar-month">
      <div className="grid grid-cols-7 border-b border-border bg-muted/60" aria-hidden="true">
        {WEEKDAY_SHORT.map((label) => (
          <div key={label} className="px-1 py-2 text-center text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {label}
          </div>
        ))}
      </div>
      <ol className="grid grid-cols-7" aria-label="Days">
        {weeks.flat().map((date) => {
          const list = byDate.get(date) ?? [];
          const inMonth = sameMonth(date, anchor);
          const isToday = date === today;
          const visible = list.length > MAX_CHIPS ? list.slice(0, MAX_CHIPS) : list;
          const hidden = list.length - visible.length;
          const dayLabel = `${fullDate(date)}${isToday ? ", today" : ""}, ${list.length === 0 ? "no classes" : `${list.length} ${list.length === 1 ? "class" : "classes"}`}`;
          return (
            <li
              key={date}
              aria-label={dayLabel}
              data-testid="calendar-day"
              data-date={date}
              data-today={isToday || undefined}
              className={cn(
                "flex min-h-20 min-w-0 flex-col gap-1 border-r border-b border-border p-1 sm:min-h-28 [&:nth-child(7n)]:border-r-0",
                !inMonth && "bg-muted/50 text-muted-foreground"
              )}
            >
              <span
                className={cn(
                  "mx-auto flex size-7 items-center justify-center rounded-full text-xs font-medium",
                  isToday && "bg-primary font-semibold text-primary-foreground ring-2 ring-primary ring-offset-2 ring-offset-background"
                )}
                aria-hidden="true"
              >
                {dayOfMonth(date) === 1 ? formatLocalDate(date, { day: "numeric", month: "short" }) : dayOfMonth(date)}
              </span>
              {/* sm and up: chips. */}
              <div className="hidden min-w-0 flex-col gap-0.5 sm:flex">
                {visible.map((occurrence) => (
                  <OccurrenceChip
                    key={occurrence.id}
                    occurrence={occurrence}
                    status={statusOf(occurrence)}
                    timeZone={timeZone}
                    onSelect={onSelect}
                  />
                ))}
                {hidden > 0 ? (
                  <button
                    type="button"
                    onClick={() => onShowDay(date)}
                    className="rounded-md px-1.5 py-0.5 text-left text-xs font-semibold text-primary outline-none hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/60"
                    aria-label={`Show all ${list.length} classes on ${fullDate(date)}`}
                    data-testid="calendar-more"
                  >
                    +{hidden} more
                  </button>
                ) : null}
              </div>
              {/* Below sm: one compact button per day with classes. */}
              {list.length > 0 ? (
                <button
                  type="button"
                  onClick={() => onShowDay(date)}
                  className="flex flex-wrap items-center justify-center gap-0.5 rounded-md p-1 outline-none focus-visible:ring-3 focus-visible:ring-ring/60 sm:hidden"
                  aria-label={`Show ${list.length} ${list.length === 1 ? "class" : "classes"} on ${fullDate(date)}`}
                >
                  {list.slice(0, 4).map((occurrence) => (
                    <span
                      key={occurrence.id}
                      className="size-2 rounded-full"
                      style={{ backgroundColor: courseColor(occurrence.courseId) }}
                      aria-hidden="true"
                    />
                  ))}
                  {list.length > 4 ? <span className="text-[10px] font-semibold" aria-hidden="true">+{list.length - 4}</span> : null}
                </button>
              ) : null}
            </li>
          );
        })}
      </ol>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Week / day time grid
// ---------------------------------------------------------------------------

const HOUR_PX = 48;
const DAY_MINUTES = 24 * 60;

interface PositionedEvent {
  occurrence: CalendarOccurrence;
  startMin: number;
  endMin: number;
  column: number;
  columns: number;
  clusterId: number;
}

/** Collapsed classes of one overlap cluster, shown as a "+N more" lane. */
interface OverflowLane {
  clusterId: number;
  startMin: number;
  endMin: number;
  column: number;
  columns: number;
  hidden: number;
}

/**
 * Caps side-by-side lanes so overlapping classes stay readable: in a cluster
 * wider than `maxLanes`, the first `maxLanes - 1` lanes are drawn and the rest
 * collapse into one "+N more" lane that opens the day's full list (nothing is
 * silently hidden).
 */
export function capLanes(events: PositionedEvent[], maxLanes: number): { shown: PositionedEvent[]; overflow: OverflowLane[] } {
  const shown: PositionedEvent[] = [];
  const overflow = new Map<number, OverflowLane>();
  for (const event of events) {
    if (event.columns <= maxLanes) {
      shown.push(event);
    } else if (event.column < maxLanes - 1) {
      shown.push({ ...event, columns: maxLanes });
    } else {
      const lane = overflow.get(event.clusterId);
      if (lane) {
        lane.hidden += 1;
        lane.startMin = Math.min(lane.startMin, event.startMin);
        lane.endMin = Math.max(lane.endMin, event.endMin);
      } else {
        overflow.set(event.clusterId, { clusterId: event.clusterId, startMin: event.startMin, endMin: event.endMin, column: maxLanes - 1, columns: maxLanes, hidden: 1 });
      }
    }
  }
  return { shown, overflow: [...overflow.values()] };
}

/** Minutes within `date` (tenant zone), clamped to that day. */
function minutesWithin(occurrence: CalendarOccurrence, date: LocalDate, timeZone: string): { startMin: number; endMin: number } {
  const start = Date.parse(occurrence.start);
  const end = Date.parse(occurrence.end);
  const startMin = localDateOf(start, timeZone) < date ? 0 : localMinutesOf(start, timeZone);
  const endMin = localDateOf(end, timeZone) > date ? DAY_MINUTES : localMinutesOf(end, timeZone);
  return { startMin, endMin: Math.max(endMin, startMin + 15) };
}

/** Overlapping events in a cluster are split into side-by-side columns. */
export function layoutDay(occurrences: CalendarOccurrence[], date: LocalDate, timeZone: string): PositionedEvent[] {
  const items = occurrences
    .map((occurrence) => ({ occurrence, ...minutesWithin(occurrence, date, timeZone) }))
    .sort((a, b) => a.startMin - b.startMin || b.endMin - a.endMin);
  const result: PositionedEvent[] = [];
  let cluster: (typeof result)[number][] = [];
  let columnEnds: number[] = [];
  let clusterEnd = -1;
  let clusterId = 0;

  const flush = () => {
    for (const event of cluster) event.columns = columnEnds.length;
    clusterId += 1;
    result.push(...cluster);
    cluster = [];
    columnEnds = [];
  };

  for (const item of items) {
    if (cluster.length > 0 && item.startMin >= clusterEnd) flush();
    let column = columnEnds.findIndex((end) => end <= item.startMin);
    if (column === -1) {
      column = columnEnds.length;
      columnEnds.push(item.endMin);
    } else {
      columnEnds[column] = item.endMin;
    }
    cluster.push({ ...item, column, columns: 1, clusterId });
    clusterEnd = cluster.length === 1 ? item.endMin : Math.max(clusterEnd, item.endMin);
  }
  if (cluster.length > 0) flush();
  return result;
}

function hourLabel(hour: number): string {
  if (hour === 0) return "12 AM";
  if (hour === 12) return "12 PM";
  return hour < 12 ? `${hour} AM` : `${hour - 12} PM`;
}

export function TimeGridView({
  occurrences,
  statusOf,
  timeZone,
  today,
  onSelect,
  days,
  nowMs,
  onShowDay,
}: ViewProps & { days: LocalDate[]; nowMs: number | null; onShowDay: (date: LocalDate) => void }) {
  const scrollRef = useRef<HTMLDivElement>(null);
  const byDate = useMemo(() => groupByDate(occurrences), [occurrences]);
  const rangeKey = days.join(",");

  const firstEventMinutes = useMemo(() => {
    let min = Number.POSITIVE_INFINITY;
    for (const occurrence of occurrences) {
      if (days.includes(occurrence.localDate)) min = Math.min(min, localMinutesOf(Date.parse(occurrence.start), timeZone));
    }
    return min;
    // eslint-disable-next-line react-hooks/exhaustive-deps -- `rangeKey` stands for `days`
  }, [occurrences, rangeKey, timeZone]);

  // Auto-scroll to ~7 AM, or a little before the first class if that is earlier, when the period changes.
  useEffect(() => {
    const target = Math.min(7 * 60, Number.isFinite(firstEventMinutes) ? firstEventMinutes - 30 : 7 * 60);
    if (scrollRef.current) scrollRef.current.scrollTop = Math.max(0, (target / 60) * HOUR_PX);
    // Only on period change, not on every refetch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rangeKey]);

  const nowDate = nowMs != null ? localDateOf(nowMs, timeZone) : null;
  const nowMinutes = nowMs != null ? localMinutesOf(nowMs, timeZone) : null;
  const singleDay = days.length === 1;

  return (
    <div className="w-full overflow-hidden rounded-xl border border-border [contain:inline-size]" data-testid={singleDay ? "calendar-day-view" : "calendar-week"}>
      <div ref={scrollRef} className="max-h-[70vh] overflow-auto" data-testid="calendar-time-scroll">
        <div className={cn("relative", !singleDay && "min-w-[640px]")}>
          {/* Column headers */}
          <div className="sticky top-0 z-20 grid border-b border-border bg-muted/90 backdrop-blur" style={{ gridTemplateColumns: `3.5rem repeat(${days.length}, minmax(0, 1fr))` }}>
            <div aria-hidden="true" />
            {days.map((date) => (
              <div key={date} className="flex flex-col items-center py-2 text-xs" data-testid="time-grid-day-header" data-date={date}>
                <span className="font-semibold uppercase tracking-wide text-muted-foreground">
                  {formatLocalDate(date, { weekday: "short" })}
                </span>
                <span
                  className={cn(
                    "mt-0.5 flex size-7 items-center justify-center rounded-full text-sm font-semibold",
                    date === today && "bg-primary text-primary-foreground"
                  )}
                >
                  {dayOfMonth(date)}
                </span>
                {date === today ? <span className="sr-only">Today</span> : null}
              </div>
            ))}
          </div>
          <div className="grid" style={{ gridTemplateColumns: `3.5rem repeat(${days.length}, minmax(0, 1fr))` }}>
            {/* Hour gutter */}
            <div className="relative" style={{ height: 24 * HOUR_PX }} aria-hidden="true">
              {Array.from({ length: 24 }, (_, hour) => (
                <span
                  key={hour}
                  className="absolute right-1.5 -translate-y-1/2 text-[11px] text-muted-foreground"
                  style={{ top: hour * HOUR_PX }}
                >
                  {hour === 0 ? "" : hourLabel(hour)}
                </span>
              ))}
            </div>
            {days.map((date) => {
              const dayEvents = byDate.get(date) ?? [];
              const { shown: events, overflow } = capLanes(layoutDay(dayEvents, date, timeZone), singleDay ? 6 : 3);
              return (
                <div key={date} className="relative border-l border-border" style={{ height: 24 * HOUR_PX }}>
                  <h3 className="sr-only">{fullDate(date)}</h3>
                  {Array.from({ length: 24 }, (_, hour) => (
                    <div key={hour} className="absolute inset-x-0 border-t border-border/70" style={{ top: hour * HOUR_PX }} aria-hidden="true" />
                  ))}
                  {events.length === 0 ? <p className="sr-only">No classes</p> : null}
                  <ul aria-label={`Classes on ${fullDate(date)}`}>
                    {events.map((event) => {
                      const status = statusOf(event.occurrence);
                      const color = courseColor(event.occurrence.courseId);
                      const cancelled = status === "CANCELLED";
                      const height = ((event.endMin - event.startMin) / 60) * HOUR_PX;
                      return (
                        <li
                          key={event.occurrence.id}
                          className="absolute px-0.5"
                          style={{
                            top: (event.startMin / 60) * HOUR_PX,
                            height,
                            left: `${(event.column / event.columns) * 100}%`,
                            width: `${100 / event.columns}%`,
                          }}
                        >
                          <button
                            type="button"
                            onClick={() => onSelect(event.occurrence)}
                            aria-label={chipLabel(event.occurrence, status, timeZone)}
                            data-testid="calendar-event"
                            data-occurrence-id={event.occurrence.id}
                            data-status={status}
                            className={cn(
                              "flex size-full flex-col overflow-hidden rounded-md px-1.5 py-1 text-left text-xs outline-none focus-visible:ring-3 focus-visible:ring-ring/60",
                              cancelled ? "border border-dashed bg-background text-muted-foreground" : "text-white shadow-sm hover:brightness-110",
                              status === "OVER" && "opacity-80"
                            )}
                            style={cancelled ? { borderColor: color } : { backgroundColor: color }}
                          >
                            <span className={cn("font-semibold leading-tight", cancelled && "line-through")}>
                              {event.occurrence.courseName}
                            </span>
                            {height >= 36 ? (
                              <span className="tabular-nums opacity-90">
                                {formatTime(Date.parse(event.occurrence.start), timeZone)} – {formatTime(Date.parse(event.occurrence.end), timeZone)}
                              </span>
                            ) : null}
                            {cancelled ? <span>Cancelled</span> : status === "LIVE" && height >= 52 ? <span>Live now</span> : null}
                          </button>
                        </li>
                      );
                    })}
                    {overflow.map((lane) => (
                      <li
                        key={`more-${lane.clusterId}`}
                        className="absolute px-0.5"
                        style={{
                          top: (lane.startMin / 60) * HOUR_PX,
                          height: Math.max(((lane.endMin - lane.startMin) / 60) * HOUR_PX, 28),
                          left: `${(lane.column / lane.columns) * 100}%`,
                          width: `${100 / lane.columns}%`,
                        }}
                      >
                        <button
                          type="button"
                          onClick={() => onShowDay(date)}
                          aria-label={`Show all ${dayEvents.length} classes on ${fullDate(date)} (${lane.hidden} more overlapping)`}
                          data-testid="calendar-more"
                          className="flex size-full items-start justify-center rounded-md border border-dashed border-primary/60 bg-primary/5 px-1 py-1 text-xs font-semibold text-primary outline-none hover:bg-primary/10 focus-visible:ring-3 focus-visible:ring-ring/60"
                        >
                          +{lane.hidden}
                          <span className="sr-only"> more</span>
                        </button>
                      </li>
                    ))}
                  </ul>
                  {nowDate === date && nowMinutes != null ? (
                    <div
                      className="pointer-events-none absolute inset-x-0 z-10 flex items-center"
                      style={{ top: (nowMinutes / 60) * HOUR_PX }}
                      data-testid="calendar-now-line"
                      aria-hidden="true"
                    >
                      <span className="-ml-1 size-2 rounded-full bg-red-600" />
                      <span className="h-0.5 flex-1 bg-red-600" />
                    </div>
                  ) : null}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Agenda list (mobile default)
// ---------------------------------------------------------------------------

export function AgendaView({
  occurrences,
  statusOf,
  timeZone,
  today,
  onSelect,
}: ViewProps) {
  const byDate = useMemo(() => groupByDate(occurrences), [occurrences]);
  const dates = Array.from(byDate.keys()).sort();
  return (
    <div className="flex flex-col gap-4" data-testid="calendar-agenda">
      {dates.map((date) => (
        <section key={date} aria-labelledby={`agenda-${date}`} className="flex flex-col gap-2">
          <h3 id={`agenda-${date}`} className="flex items-center gap-2 text-sm font-semibold text-foreground">
            {fullDate(date)}
            {date === today ? (
              <span className="rounded-full bg-primary px-2 py-0.5 text-[11px] font-semibold text-primary-foreground">Today</span>
            ) : null}
          </h3>
          <ul className="flex flex-col gap-2">
            {(byDate.get(date) ?? []).map((occurrence) => {
              const status = statusOf(occurrence);
              return (
                <li key={occurrence.id}>
                  <button
                    type="button"
                    onClick={() => onSelect(occurrence)}
                    aria-label={chipLabel(occurrence, status, timeZone)}
                    data-testid="calendar-agenda-item"
                    data-occurrence-id={occurrence.id}
                    className="flex w-full min-w-0 items-stretch gap-3 rounded-lg border border-border bg-card p-3 text-left outline-none hover:bg-muted/50 focus-visible:ring-3 focus-visible:ring-ring/60"
                  >
                    <span className="w-1 shrink-0 rounded-full" style={{ backgroundColor: courseColor(occurrence.courseId) }} aria-hidden="true" />
                    <span className="flex min-w-0 flex-1 flex-col gap-1">
                      <span className={cn("truncate font-medium", status === "CANCELLED" && "line-through")}>{occurrence.courseName}</span>
                      <span className="text-xs tabular-nums text-muted-foreground">
                        {formatTime(Date.parse(occurrence.start), timeZone)} – {formatTime(Date.parse(occurrence.end), timeZone)}
                        {occurrence.teacherNames.length > 0 ? ` · ${occurrence.teacherNames.join(", ")}` : ""}
                      </span>
                    </span>
                    <ScheduleStatusBadge status={status} className="self-center" />
                  </button>
                </li>
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}
