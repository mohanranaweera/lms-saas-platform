/**
 * Small helpers converting between an ISO-8601 instant string (what the
 * backend's `ExamResponse.scheduledStart`/`scheduledEnd` sends/expects) and
 * the local-time value an `<input type="datetime-local">` needs
 * (`YYYY-MM-DDTHH:mm`, no timezone). Both directions go through the
 * browser's local timezone, matching how the input itself behaves.
 */

export function isoToDatetimeLocal(iso: string): string {
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}`;
}

export function datetimeLocalToIso(value: string): string {
  return new Date(value).toISOString();
}

function zonedFields(iso: string, timeZone?: string): Record<string, string> | null {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  let formatter: Intl.DateTimeFormat;
  try {
    formatter = new Intl.DateTimeFormat("en-US", {
      timeZone: timeZone || undefined,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    });
  } catch {
    formatter = new Intl.DateTimeFormat("en-US", {
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
    });
  }
  const parts: Record<string, string> = {};
  for (const part of formatter.formatToParts(date)) parts[part.type] = part.value;
  if (parts.hour === "24") parts.hour = "00";
  return parts;
}

/**
 * STU-MGMT-02: an ISO instant as `YYYY-MM-DD HH:mm` (24 h) in the tenant's
 * IANA timezone (falls back to the browser zone when none/invalid). Returns
 * `fallback` for a null/unparseable value.
 */
export function formatZonedDateTime(iso: string | null | undefined, timeZone?: string, fallback = "—"): string {
  if (!iso) return fallback;
  const p = zonedFields(iso, timeZone);
  if (!p) return fallback;
  return `${p.year}-${p.month}-${p.day} ${p.hour}:${p.minute}`;
}

/** The `YYYY-MM-DD` calendar date of an ISO instant in `timeZone` (null when unparseable). */
export function zonedDateKey(iso: string | null | undefined, timeZone?: string): string | null {
  if (!iso) return null;
  const p = zonedFields(iso, timeZone);
  return p ? `${p.year}-${p.month}-${p.day}` : null;
}

/** True when the instant's tenant-zone date lies inside the inclusive `YYYY-MM-DD` range (open ends allowed). */
export function isWithinDateRange(
  iso: string | null | undefined,
  range: { from?: string; to?: string },
  timeZone?: string
): boolean {
  if (!range.from && !range.to) return true;
  const key = zonedDateKey(iso, timeZone);
  if (!key) return false;
  if (range.from && key < range.from) return false;
  if (range.to && key > range.to) return false;
  return true;
}
