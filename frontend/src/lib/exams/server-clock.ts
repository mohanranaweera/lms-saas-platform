import { useCallback, useEffect, useState } from "react";

/**
 * Server-anchored clock for the exam countdown. The offset between the
 * server's `serverNow` and the browser clock is measured when a response
 * arrives, so a wrong device clock does not change the displayed remaining
 * time. Display only — the server finalizes attempts at the deadline on its
 * own clock regardless of what the browser shows.
 */
export function serverOffsetMs(serverNow: string | null | undefined, receivedAtMs = Date.now()): number | null {
  if (!serverNow) return null;
  const server = new Date(serverNow).getTime();
  return Number.isNaN(server) ? null : server - receivedAtMs;
}

/** `H:MM:SS` or `M:SS`, clamped at zero. */
export function formatCountdown(remainingMs: number): string {
  const total = Math.max(0, Math.ceil(remainingMs / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const ss = String(seconds).padStart(2, "0");
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, "0")}:${ss}` : `${minutes}:${ss}`;
}

/**
 * Ticks once a second and returns the remaining milliseconds until
 * `deadlineAt` on the server clock. `resync(serverNow)` updates the offset
 * (call it whenever a response carries a fresh `serverNow`).
 */
export function useServerCountdown(
  deadlineAt: string | null,
  anchorServerNow: string | null,
  /** Browser time (ms) when the response carrying `anchorServerNow` arrived, e.g. React Query's `dataUpdatedAt`. */
  anchorReceivedAtMs: number
) {
  const [offset, setOffset] = useState<number>(() => serverOffsetMs(anchorServerNow, anchorReceivedAtMs) ?? 0);
  const [anchor, setAnchor] = useState(`${anchorServerNow}|${anchorReceivedAtMs}`);
  const [nowMs, setNowMs] = useState<number>(() => Date.now());

  // A new attempt payload (e.g. after a refetch) re-anchors the clock.
  const nextAnchor = `${anchorServerNow}|${anchorReceivedAtMs}`;
  if (nextAnchor !== anchor) {
    setAnchor(nextAnchor);
    const next = serverOffsetMs(anchorServerNow, anchorReceivedAtMs);
    if (next !== null) setOffset(next);
  }

  useEffect(() => {
    const handle = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(handle);
  }, []);

  const resync = useCallback((serverNow: string | null | undefined) => {
    const next = serverOffsetMs(serverNow);
    if (next === null) return;
    setOffset(next);
    setNowMs(Date.now());
  }, []);

  const deadline = deadlineAt ? new Date(deadlineAt).getTime() : Number.NaN;
  const remainingMs = Number.isNaN(deadline) ? null : deadline - (nowMs + offset);
  return { remainingMs, resync };
}
