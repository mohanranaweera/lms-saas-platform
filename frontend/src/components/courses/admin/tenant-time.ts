/**
 * Date/time helpers that work in an explicit IANA timezone (the tenant's),
 * not the browser's. Inputs use `YYYY-MM-DD` and `HH:mm` strings, as produced
 * by `<input type="date">` / `<input type="time">`. Output is an ISO instant
 * for the API, which checks windows against canonical instants.
 */

interface ZonedParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

function zonedParts(date: Date, timeZone: string): ZonedParts {
  const formatter = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  });
  const parts: Record<string, string> = {};
  for (const part of formatter.formatToParts(date)) {
    parts[part.type] = part.value;
  }
  return {
    year: Number(parts.year),
    month: Number(parts.month),
    day: Number(parts.day),
    hour: Number(parts.hour) % 24,
    minute: Number(parts.minute),
    second: Number(parts.second),
  };
}

/** Offset of `timeZone` from UTC at `date`, in milliseconds. */
function offsetMs(date: Date, timeZone: string): number {
  const p = zonedParts(date, timeZone);
  const asUtc = Date.UTC(p.year, p.month - 1, p.day, p.hour, p.minute, p.second);
  const truncated = Math.floor(date.getTime() / 1000) * 1000;
  return asUtc - truncated;
}

const pad = (value: number) => String(value).padStart(2, "0");

/** Wall-clock date + time in `timeZone` → ISO instant. Returns `null` for malformed input. */
export function zonedDateTimeToIso(date: string, time: string, timeZone: string): string | null {
  const dateMatch = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  const timeMatch = /^(\d{2}):(\d{2})$/.exec(time);
  if (!dateMatch || !timeMatch) return null;
  const [, y, m, d] = dateMatch;
  const [, hh, mm] = timeMatch;
  const wallAsUtc = Date.UTC(Number(y), Number(m) - 1, Number(d), Number(hh), Number(mm));
  if (!Number.isFinite(wallAsUtc)) return null;
  // Two passes settle DST transitions: the offset at the guessed instant may
  // differ from the offset at the real one.
  let instant = wallAsUtc - offsetMs(new Date(wallAsUtc), timeZone);
  const second = offsetMs(new Date(instant), timeZone);
  instant = wallAsUtc - second;
  return new Date(instant).toISOString();
}

/** ISO instant → `{date, time}` wall-clock strings in `timeZone`. */
export function isoToZonedInputs(iso: string, timeZone: string): { date: string; time: string } {
  const p = zonedParts(new Date(iso), timeZone);
  return {
    date: `${p.year}-${pad(p.month)}-${pad(p.day)}`,
    time: `${pad(p.hour)}:${pad(p.minute)}`,
  };
}

/** "Now" in `timeZone`, as input strings (minutes precision). */
export function nowZonedInputs(timeZone: string): { date: string; time: string } {
  return isoToZonedInputs(new Date().toISOString(), timeZone);
}

/** Table display: `YYYY-MM-DD HH:mm` in `timeZone`. */
export function formatZonedDateTime(iso: string | null | undefined, timeZone: string): string {
  if (!iso) return "";
  const { date, time } = isoToZonedInputs(iso, timeZone);
  return `${date} ${time}`;
}
