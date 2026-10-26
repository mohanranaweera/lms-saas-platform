import { formatReportDecimal } from "@/lib/api/reports";
import { MONTH_SHORT_NAMES } from "@/lib/validation/reports";

/**
 * RPT-02 display formatting. Formats server values only - never parses money
 * to a float, never sums.
 */

/** "LKR 20,000.00" / "-LKR 4,000.00" (the reference's currency-code style). */
export function formatCodeAmount(amount: string, currency: string): string {
  const formatted = formatReportDecimal(amount);
  return formatted.startsWith("-") ? `-${currency} ${formatted.slice(1)}` : `${currency} ${formatted}`;
}

/** A 2-dp server decimal string that is exactly zero (string check, no float maths). */
export function isZeroAmount(amount: string | null | undefined): boolean {
  if (!amount) return true;
  return /^[-+]?0*(\.0*)?$/.test(amount.trim());
}

/** "2026-10-08" -> "08 Oct 2026" (no timezone conversion: it is already a local date). */
export function formatLocalDate(date: string): string {
  const [year, month, day] = date.split("-");
  const name = MONTH_SHORT_NAMES[Number(month) - 1];
  return name && day && year ? `${day} ${name} ${year}` : date;
}

/** ISO instant -> "08 Oct 2026, 05:54 pm" in the report's timezone. */
export function formatInstant(iso: string, timezone: string): string {
  try {
    return new Intl.DateTimeFormat("en-GB", {
      day: "2-digit",
      month: "short",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hour12: true,
      timeZone: timezone,
    }).format(new Date(iso));
  } catch {
    return iso;
  }
}

/** ISO instant -> { date: "08 Oct 2026", time: "05:54 pm" } in the report's timezone (for two-line cells). */
export function formatInstantParts(iso: string, timezone: string): { date: string; time: string | null } {
  try {
    const instant = new Date(iso);
    const date = new Intl.DateTimeFormat("en-GB", { day: "2-digit", month: "short", year: "numeric", timeZone: timezone }).format(instant);
    const time = new Intl.DateTimeFormat("en-GB", { hour: "2-digit", minute: "2-digit", hour12: true, timeZone: timezone }).format(instant);
    return { date, time };
  } catch {
    return { date: iso, time: null };
  }
}
