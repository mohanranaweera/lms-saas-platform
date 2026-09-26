import type { AttendanceRecordResponse } from "@/lib/api/attendance";
import { shortId } from "@/lib/format";

/**
 * Display label for the teaching session an attendance record belongs to
 * (Wave 8). A class-session record shows its session title; a legacy
 * (pre-Wave-8, lesson-scoped) record is labelled explicitly as such — its
 * `sessionId` is a lesson id, not a class occurrence, so it must never be
 * presented as if it were one.
 */
export function formatAttendanceSession(
  record: Pick<AttendanceRecordResponse, "source" | "classSessionId" | "classSessionTitle" | "sessionId">
): string {
  if (record.classSessionTitle) return record.classSessionTitle;
  if (record.classSessionId) return shortId(record.classSessionId, "Session");
  if (record.sessionId) return `${shortId(record.sessionId, "Lesson")} (legacy)`;
  return "Unknown session";
}
