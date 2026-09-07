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
