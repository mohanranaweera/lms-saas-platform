"use client";

import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { apiFetch } from "./client";
import { saveBlob, useAuthorizedBlobFetch } from "./reports";
import type { ScheduleStatus } from "@/lib/calendar/status";

/**
 * CAL-01 calendar client (docs/specs/class-calendar.md §6.3–§6.6). Scope
 * (which courses a caller sees) is decided entirely server-side from the
 * authenticated session; the optional `courseId` filter is only a narrowing
 * request that the server re-checks (404 outside scope). Nothing here creates
 * a session, starts a meeting, marks attendance or grants access.
 */

export type CalendarView = "MONTH" | "WEEK" | "DAY";

export interface CalendarOccurrence {
  id: string;
  source: "RECURRING" | "SESSION";
  courseId: string;
  courseName: string;
  courseSlug: string;
  start: string;
  end: string;
  localDate: string;
  startLocalTime: string;
  endLocalTime: string;
  teacherNames: string[];
  status: ScheduleStatus;
  cancelled: boolean;
  sessionId: string | null;
  sessionTitle: string | null;
  replacesOccurrenceId: string | null;
}

export interface CalendarOccurrencesResponse {
  timezone: string;
  serverNow: string;
  from: string;
  to: string;
  occurrences: CalendarOccurrence[];
  /** Client-only: local `Date.now()` when the response arrived (server-clock offset). */
  receivedAt: number;
}

export interface CalendarCourseOption {
  id: string;
  name: string;
}

export interface CalendarRange {
  from: string;
  to: string;
  courseId: string | null;
}

export const calendarKeys = {
  all: ["calendar"] as const,
  occurrences: (range: CalendarRange) =>
    ["calendar", "occurrences", range.from, range.to, range.courseId ?? "all"] as const,
  courses: () => ["calendar", "courses"] as const,
};

function rangeQuery(range: CalendarRange, extra: Record<string, string> = {}): string {
  const params = new URLSearchParams({ from: range.from, to: range.to, ...extra });
  if (range.courseId) params.set("courseId", range.courseId);
  return params.toString();
}

/**
 * `GET /v1/calendar/occurrences`. The query function passes TanStack's
 * `signal`, so a period the user has already navigated away from is aborted
 * and never rendered; `keepPreviousData` keeps the old grid visible (with a
 * subtle loading indicator) while the next period loads.
 */
export function useCalendarOccurrences(range: CalendarRange, enabled = true) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: calendarKeys.occurrences(range),
    queryFn: async ({ signal }) => {
      const data = await authorizedFetch<Omit<CalendarOccurrencesResponse, "receivedAt">>(
        "tenant",
        `/v1/calendar/occurrences?${rangeQuery(range)}`,
        { signal }
      );
      return { ...data, occurrences: data.occurrences ?? [], receivedAt: Date.now() };
    },
    placeholderData: keepPreviousData,
    enabled,
    refetchInterval: 5 * 60_000,
  });
}

/** `GET /v1/calendar/courses` — filter options in the caller's scope, sorted by name. */
export function useCalendarCourses() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: calendarKeys.courses(),
    queryFn: ({ signal }) => authorizedFetch<CalendarCourseOption[]>("tenant", "/v1/calendar/courses", { signal }),
    staleTime: 5 * 60_000,
  });
}

/**
 * `GET /v1/calendar/timetable.pdf` — the server renders every occurrence in
 * the range (never a DOM subset). Downloaded with the authenticated blob
 * fetch shared with report PDFs; the bytes never touch a public URL.
 */
export function useDownloadTimetable() {
  const fetchBlob = useAuthorizedBlobFetch();
  return useMutation({
    mutationFn: async (input: CalendarRange & { view: CalendarView }) => {
      const blob = await fetchBlob(`/v1/calendar/timetable.pdf?${rangeQuery(input, { view: input.view })}`);
      const filename = `timetable-${input.from}-${input.to}.pdf`;
      saveBlob(blob, filename);
      return filename;
    },
  });
}

// ---------------------------------------------------------------------------
// Public "Today's Classes" (§6.6) — anonymous, tenant resolved from the host.
// ---------------------------------------------------------------------------

export interface PublicTodayOccurrence {
  id: string;
  courseName: string;
  courseSlug: string;
  coverUrl: string | null;
  teacherName: string | null;
  teacherPhotoUrl: string | null;
  start: string;
  end: string;
  startLocalTime: string;
  endLocalTime: string;
  status: ScheduleStatus;
}

export interface PublicTodayResponse {
  enabled: boolean;
  timezone: string;
  serverNow: string;
  date: string;
  occurrences: PublicTodayOccurrence[];
  receivedAt: number;
}

function currentHost(): string {
  return typeof window === "undefined" ? "server" : window.location.host;
}

export const publicTodayKeys = {
  all: ["public-today-classes"] as const,
  host: (host: string) => ["public-today-classes", host] as const,
};

export function usePublicTodayClasses() {
  return useQuery({
    queryKey: publicTodayKeys.host(currentHost()),
    queryFn: async ({ signal }) => {
      const data = await apiFetch<Omit<PublicTodayResponse, "receivedAt">>("/v1/public/calendar/today", { signal });
      return { ...data, occurrences: data.occurrences ?? [], receivedAt: Date.now() };
    },
    retry: false,
    staleTime: 30_000,
    refetchInterval: 5 * 60_000,
    refetchOnWindowFocus: "always",
  });
}
