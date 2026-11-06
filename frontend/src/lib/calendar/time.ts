/**
 * CAL-01 tenant-zone date/time helpers (docs/specs/class-calendar.md §3).
 *
 * Every calendar date is a tenant-local `YYYY-MM-DD` string and every instant
 * is epoch milliseconds. The browser's own time zone is never used to decide
 * which local day an instant falls on: all conversions go through
 * `Intl.DateTimeFormat` with the tenant's IANA `timezone` from the API
 * response. Pure local-date arithmetic (add days, weekday, month grid) is done
 * on a UTC calendar, which has no DST, so it is zone-independent.
 */

const DAY_MS = 86_400_000;

export type LocalDate = string; // YYYY-MM-DD

const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;

export function isLocalDate(value: string | null | undefined): value is LocalDate {
  if (!value) return false;
  const match = DATE_PATTERN.exec(value);
  if (!match) return false;
  const date = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return toLocalDate(date) === value;
}

function toLocalDate(date: Date): LocalDate {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getUTCFullYear()}-${pad(date.getUTCMonth() + 1)}-${pad(date.getUTCDate())}`;
}

function parse(date: LocalDate): Date {
  const match = DATE_PATTERN.exec(date);
  if (!match) throw new Error(`Invalid local date: ${date}`);
  return new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
}

export function addDays(date: LocalDate, days: number): LocalDate {
  return toLocalDate(new Date(parse(date).getTime() + days * DAY_MS));
}

/** Adds whole months, landing on day 1 of the resulting month. */
export function addMonths(date: LocalDate, months: number): LocalDate {
  const d = parse(date);
  return toLocalDate(new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + months, 1)));
}

/** 0 = Sunday … 6 = Saturday. */
export function weekdayIndex(date: LocalDate): number {
  return parse(date).getUTCDay();
}

export function startOfMonth(date: LocalDate): LocalDate {
  return `${date.slice(0, 7)}-01`;
}

/** Sunday on or before `date`. */
export function startOfWeek(date: LocalDate): LocalDate {
  return addDays(date, -weekdayIndex(date));
}

export function sameMonth(a: LocalDate, b: LocalDate): boolean {
  return a.slice(0, 7) === b.slice(0, 7);
}

export function dayOfMonth(date: LocalDate): number {
  return parse(date).getUTCDate();
}

/** Inclusive-exclusive list of dates `[from, to)`. */
export function datesBetween(from: LocalDate, to: LocalDate): LocalDate[] {
  const out: LocalDate[] = [];
  for (let d = from; d < to; d = addDays(d, 1)) out.push(d);
  return out;
}

interface ZonedParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

const partsFormatters = new Map<string, Intl.DateTimeFormat>();

function partsFormatter(timeZone: string): Intl.DateTimeFormat {
  let formatter = partsFormatters.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat("en-US", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    partsFormatters.set(timeZone, formatter);
  }
  return formatter;
}

/** Falls back to UTC when the zone is missing or unknown to this browser (mirrors the server's UTC fallback). */
export function safeTimeZone(timeZone: string | null | undefined): string {
  if (!timeZone) return "UTC";
  try {
    new Intl.DateTimeFormat("en-US", { timeZone });
    return timeZone;
  } catch {
    return "UTC";
  }
}

export function zonedParts(ms: number, timeZone: string): ZonedParts {
  const values: Record<string, number> = {};
  for (const part of partsFormatter(timeZone).formatToParts(new Date(ms))) {
    if (part.type !== "literal") values[part.type] = Number(part.value);
  }
  return {
    year: values.year,
    month: values.month,
    day: values.day,
    hour: values.hour === 24 ? 0 : values.hour,
    minute: values.minute,
    second: values.second,
  };
}

/** Tenant-local date of an instant. */
export function localDateOf(ms: number, timeZone: string): LocalDate {
  const p = zonedParts(ms, timeZone);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${p.year}-${pad(p.month)}-${pad(p.day)}`;
}

/** Minutes since tenant-local midnight of the instant's own local day. */
export function localMinutesOf(ms: number, timeZone: string): number {
  const p = zonedParts(ms, timeZone);
  return p.hour * 60 + p.minute + p.second / 60;
}

function offsetMs(ms: number, timeZone: string): number {
  const p = zonedParts(ms, timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  return asUtc - Math.floor(ms / 1000) * 1000;
}

/**
 * Instant of tenant-local `date` 00:00 (`localDate.atStartOfDay(zone)`, never
 * "+24h"). Two offset passes resolve DST transitions.
 */
export function zonedStartOfDay(date: LocalDate, timeZone: string): number {
  const utcMidnight = parse(date).getTime();
  let guess = utcMidnight - offsetMs(utcMidnight, timeZone);
  guess = utcMidnight - offsetMs(guess, timeZone);
  return guess;
}

/** Next tenant-local midnight strictly after `ms`. */
export function nextLocalMidnight(ms: number, timeZone: string): number {
  return zonedStartOfDay(addDays(localDateOf(ms, timeZone), 1), timeZone);
}

/** Formats a local date without any zone shifting (rendered on the UTC calendar at noon). */
export function formatLocalDate(date: LocalDate, options: Intl.DateTimeFormatOptions, locale = "en-GB"): string {
  return new Intl.DateTimeFormat(locale, { ...options, timeZone: "UTC" }).format(
    new Date(parse(date).getTime() + DAY_MS / 2)
  );
}

/** "8:00 AM" for an instant in the tenant zone. */
export function formatTime(ms: number, timeZone: string): string {
  return new Intl.DateTimeFormat("en-US", { timeZone, hour: "numeric", minute: "2-digit" }).format(new Date(ms));
}

/** "8:00 AM" for a wall-clock `HH:mm`. */
export function formatWallTime(hhmm: string): string {
  const [h, m] = hhmm.split(":").map(Number);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return hhmm;
  const period = h >= 12 ? "PM" : "AM";
  const hour = h % 12 === 0 ? 12 : h % 12;
  return `${hour}:${String(m).padStart(2, "0")} ${period}`;
}

const ZONE_NAMES: Record<string, string> = {
  "Asia/Colombo": "Sri Lanka time",
  UTC: "UTC",
  "Etc/UTC": "UTC",
};

/** "Sri Lanka time (Asia/Colombo)"; other zones fall back to "<City> time (<zone>)". */
export function timeZoneLabel(timeZone: string): string {
  const known = ZONE_NAMES[timeZone];
  if (known === "UTC") return "UTC";
  if (known) return `${known} (${timeZone})`;
  const city = timeZone.split("/").pop()?.replace(/_/g, " ") ?? timeZone;
  return `${city} time (${timeZone})`;
}

/** "Friday, 6 November 2026". */
export function formatLongDate(date: LocalDate): string {
  return `${formatLocalDate(date, { weekday: "long" })}, ${formatLocalDate(date, { day: "numeric", month: "long", year: "numeric" })}`;
}
