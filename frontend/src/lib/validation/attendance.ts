import { z } from "zod";
import type { AttendanceListParams } from "@/lib/api/attendance";

/**
 * Zod schema for the Attendance Reports / My Attendance date-range +
 * course filter form (shared by the Teacher Reports, Student My Attendance,
 * and Tenant Admin Reports screens — see
 * `components/attendance/attendance-filter-form.tsx`). Mirrors
 * `lib/validation/course.ts`'s style: this is UX convenience only (the
 * backend independently and authoritatively re-validates), scoped here to
 * the one cross-row rule the backend also enforces — `from` must not be
 * after `to` (plan §12).
 *
 * `courseId`/`from`/`to` are all plain form-level strings (native
 * `<input type="date">` values, i.e. bare `YYYY-MM-DD`) so a controlled
 * `Input` always has a defined value; `toAttendanceQueryParams` below
 * converts a day-granularity selection to the full ISO-8601 `Instant`
 * strings `GET /api/v1/attendance/my`/`/reports` actually require
 * (start-of-day for `from`, end-of-day for `to`) only at query-build time.
 */

/** Sentinel for the course `Select`'s "All courses" option — `base-ui`'s `Select` cannot use an empty-string item value, mirroring `teacher/courses/page.tsx`'s own `"all"` sentinel convention. Never sent as a query param (see `toAttendanceQueryParams`). */
export const ALL_COURSES_VALUE = "all";

export const attendanceFilterSchema = z
  .object({
    courseId: z.string(),
    /** Bare `YYYY-MM-DD`, or empty string for "no lower bound". */
    from: z.string(),
    /** Bare `YYYY-MM-DD`, or empty string for "no upper bound". */
    to: z.string(),
  })
  .refine((values) => !values.from || !values.to || values.from <= values.to, {
    message: "\"From\" date must not be after \"to\" date.",
    path: ["to"],
  });

export type AttendanceFilterFormValues = z.infer<typeof attendanceFilterSchema>;

export const ATTENDANCE_FILTER_DEFAULT_VALUES: AttendanceFilterFormValues = {
  courseId: ALL_COURSES_VALUE,
  from: "",
  to: "",
};

/**
 * Converts a validated day-granularity filter selection into the
 * `courseId`/`from`/`to` shape `useMyAttendance`/`useAttendanceReports`
 * expect: `from` becomes that day's start-of-day instant, `to` becomes that
 * day's end-of-day instant (`23:59:59.999`), both in UTC. Omits any field
 * left blank rather than sending an empty string.
 */
export function toAttendanceQueryParams(
  values: AttendanceFilterFormValues
): Pick<AttendanceListParams, "courseId" | "from" | "to"> {
  return {
    courseId: values.courseId && values.courseId !== ALL_COURSES_VALUE ? values.courseId : undefined,
    from: values.from ? `${values.from}T00:00:00.000Z` : undefined,
    to: values.to ? `${values.to}T23:59:59.999Z` : undefined,
  };
}
