/**
 * Display helpers for exam schedules (Klass format "09 Jun 2024, 08:58 am").
 * Shown in the browser's local timezone. Relative phrases ("2 years ago")
 * are computed against the server's `serverNow`, never the browser clock, so
 * they agree with the server-derived exam status.
 */

const DATE_TIME = new Intl.DateTimeFormat("en-GB", {
  day: "2-digit",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hour12: true,
});

export function formatExamDateTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";
  return DATE_TIME.format(date).replace(/\b(AM|PM)\b/, (match) => match.toLowerCase());
}

const RELATIVE = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [
  ["year", 365 * 24 * 60 * 60 * 1000],
  ["month", 30 * 24 * 60 * 60 * 1000],
  ["week", 7 * 24 * 60 * 60 * 1000],
  ["day", 24 * 60 * 60 * 1000],
  ["hour", 60 * 60 * 1000],
  ["minute", 60 * 1000],
];

/** "2 years ago" / "in 3 days" for `iso` relative to `nowIso` (the server's clock). */
export function relativeToServerNow(iso: string, nowIso: string): string {
  const target = new Date(iso).getTime();
  const now = new Date(nowIso).getTime();
  if (Number.isNaN(target) || Number.isNaN(now)) return "";
  const diff = target - now;
  for (const [unit, ms] of UNITS) {
    if (Math.abs(diff) >= ms) return RELATIVE.format(Math.trunc(diff / ms), unit);
  }
  return diff >= 0 ? "in less than a minute" : "just now";
}

export function formatDuration(minutes: number | null | undefined): string | null {
  if (minutes === null || minutes === undefined) return null;
  return `${minutes} ${minutes === 1 ? "minute" : "minutes"}`;
}
