"use client";

import { useEffect, useMemo, useState } from "react";
import { nextLocalMidnight } from "./time";

/**
 * Schedule-based class status (docs/specs/class-calendar.md §4). "Live now"
 * means the scheduled time window, never provider telemetry. The API returns
 * `status` at its `serverNow`; the UI recomputes it with a server-offset
 * clock so cards move Scheduled → Live now → Over without a reload.
 */
export type ScheduleStatus = "SCHEDULED" | "LIVE" | "OVER" | "CANCELLED";

export interface StatusInput {
  start: string;
  end: string;
  status?: ScheduleStatus;
  cancelled?: boolean;
}

/** Half-open `[start, end)`: exactly at start = LIVE, exactly at end = OVER. */
export function occurrenceStatus(occurrence: StatusInput, nowMs: number | null): ScheduleStatus {
  if (occurrence.cancelled || occurrence.status === "CANCELLED") return "CANCELLED";
  if (nowMs == null) return occurrence.status ?? "SCHEDULED";
  const start = Date.parse(occurrence.start);
  const end = Date.parse(occurrence.end);
  if (nowMs < start) return "SCHEDULED";
  if (nowMs < end) return "LIVE";
  return "OVER";
}

/**
 * Offset between the server clock and this browser's clock, from a response's
 * `serverNow` and the local time it was received (`receivedAt`, stamped in the
 * query function). Using the receive time — not "now" at render — keeps the
 * offset correct when a cached response is shown again later.
 */
export function useServerClock(serverNow: string | null | undefined, receivedAt: number | null | undefined): number | null {
  return useMemo(() => {
    if (!serverNow || receivedAt == null) return null;
    const server = Date.parse(serverNow);
    return Number.isFinite(server) ? server - receivedAt : null;
  }, [serverNow, receivedAt]);
}

const MAX_DELAY_MS = 60 * 60_000;

/**
 * The single shared next-boundary scheduler. Returns the server-adjusted
 * "now" (ms), re-rendering only when it crosses the nearest upcoming class
 * start/end, the next tenant-local midnight, or (when `minuteTicks`) the next
 * minute for a current-time line. One `setTimeout` per consumer, re-armed
 * after it fires — never a timer per card. Also refreshes when the tab
 * becomes visible again (timers are throttled in background tabs).
 *
 * Returns `null` until the first client-side tick, so server-rendered and
 * first-paint output fall back to the API's own `status`.
 */
export function useScheduleNow({
  offsetMs,
  events,
  timeZone,
  minuteTicks = false,
}: {
  offsetMs: number | null;
  events: readonly { start: string; end: string }[];
  timeZone: string | null;
  minuteTicks?: boolean;
}): number | null {
  const [now, setNow] = useState<number | null>(null);

  const boundaries = useMemo(() => {
    const values: number[] = [];
    for (const event of events) {
      const start = Date.parse(event.start);
      const end = Date.parse(event.end);
      if (Number.isFinite(start)) values.push(start);
      if (Number.isFinite(end)) values.push(end);
    }
    return values.sort((a, b) => a - b);
  }, [events]);

  useEffect(() => {
    if (offsetMs == null) return;
    const clock = () => Date.now() + offsetMs;
    const current = clock();
    let delay: number;
    if (now == null || Math.abs(current - now) > 2_000) {
      delay = 0;
    } else {
      let next = Number.POSITIVE_INFINITY;
      for (const boundary of boundaries) {
        if (boundary > current) {
          next = boundary;
          break;
        }
      }
      if (timeZone) next = Math.min(next, nextLocalMidnight(current, timeZone));
      if (minuteTicks) next = Math.min(next, (Math.floor(current / 60_000) + 1) * 60_000);
      delay = Math.min(Math.max(next - current + 1, 0), MAX_DELAY_MS);
    }
    const timer = setTimeout(() => setNow(clock()), delay);
    const onVisible = () => {
      if (document.visibilityState === "visible") setNow(clock());
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [now, offsetMs, boundaries, timeZone, minuteTicks]);

  return offsetMs == null ? null : now;
}
