import { WEEKDAYS, WEEKDAY_LABELS, type Weekday } from "@/lib/api/class-schedule";
import { formatWallTime } from "./time";

/**
 * Client-side mirror of the server's class-schedule validation
 * (docs/specs/class-calendar.md §2.3). UX only: the backend re-validates every
 * save and its `VALIDATION_ERROR` field messages are shown too.
 */

export interface ScheduleRowDraft {
  key: string;
  dayOfWeek: Weekday;
  startTime: string;
  endTime: string;
}

export interface ScheduleRowErrors {
  startTime?: string;
  endTime?: string;
  row?: string;
}

export interface ScheduleValidationResult {
  rows: Record<string, ScheduleRowErrors>;
  effectiveFrom?: string;
  effectiveTo?: string;
  form?: string;
  valid: boolean;
}

export const MAX_SLOTS = 50;

export const OVERNIGHT_MESSAGE =
  "End time must be after start time. Overnight classes aren't supported — split them into two rows.";

const TIME_PATTERN = /^([01]\d|2[0-3]):[0-5]\d$/;

/** Monday→Sunday, then start time; rows without a start time sort last within their day. */
export function sortRows<T extends { dayOfWeek: Weekday; startTime: string }>(rows: T[]): T[] {
  return [...rows].sort((a, b) => {
    const day = WEEKDAYS.indexOf(a.dayOfWeek) - WEEKDAYS.indexOf(b.dayOfWeek);
    if (day !== 0) return day;
    if (!a.startTime) return b.startTime ? 1 : 0;
    if (!b.startTime) return -1;
    return a.startTime.localeCompare(b.startTime);
  });
}

export function validateSchedule(
  rows: ScheduleRowDraft[],
  effectiveFrom: string,
  effectiveTo: string
): ScheduleValidationResult {
  const result: ScheduleValidationResult = { rows: {}, valid: true };
  const fail = (key: string, field: keyof ScheduleRowErrors, message: string) => {
    result.rows[key] = { ...result.rows[key], [field]: result.rows[key]?.[field] ?? message };
    result.valid = false;
  };

  for (const row of rows) {
    if (!row.startTime) fail(row.key, "startTime", "Start time is required.");
    else if (!TIME_PATTERN.test(row.startTime)) fail(row.key, "startTime", "Enter a time as HH:MM.");
    if (!row.endTime) fail(row.key, "endTime", "End time is required.");
    else if (!TIME_PATTERN.test(row.endTime)) fail(row.key, "endTime", "Enter a time as HH:MM.");
    if (TIME_PATTERN.test(row.startTime) && TIME_PATTERN.test(row.endTime) && row.endTime <= row.startTime) {
      fail(row.key, "endTime", OVERNIGHT_MESSAGE);
    }
  }

  // Duplicates and same-day overlaps, only among otherwise-valid rows.
  for (const day of WEEKDAYS) {
    const sameDay = sortRows(
      rows.filter(
        (row) => row.dayOfWeek === day && !result.rows[row.key] && row.startTime && row.endTime
      )
    );
    const seen = new Set<string>();
    let previous: ScheduleRowDraft | null = null;
    for (const row of sameDay) {
      const signature = `${row.startTime}-${row.endTime}`;
      if (seen.has(signature)) {
        fail(row.key, "row", `This ${WEEKDAY_LABELS[day]} class time is a duplicate. Remove one of the rows.`);
        continue;
      }
      seen.add(signature);
      if (previous && row.startTime < previous.endTime) {
        fail(
          row.key,
          "row",
          `Overlaps another ${WEEKDAY_LABELS[day]} class (${formatWallTime(previous.startTime)} – ${formatWallTime(previous.endTime)}). Classes of the same course can't overlap.`
        );
        continue;
      }
      previous = row;
    }
  }

  if (effectiveFrom && effectiveTo && effectiveFrom > effectiveTo) {
    result.effectiveTo = "To date must be on or after From date.";
    result.valid = false;
  }
  if (rows.length > MAX_SLOTS) {
    result.form = `A course can have at most ${MAX_SLOTS} class times.`;
    result.valid = false;
  }
  return result;
}
