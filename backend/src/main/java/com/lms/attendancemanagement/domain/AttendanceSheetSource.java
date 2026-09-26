package com.lms.attendancemanagement.domain;

/**
 * Mirrors {@code ck_attendance_sheet_source} (V56) exactly.
 *
 * <ul>
 * <li>{@link #CLASS_SESSION} - the Wave 8 workflow: one sheet per {@code
 * class_session}, the real teaching occurrence.</li>
 * <li>{@link #LEGACY_LESSON} - one sheet per {@code course_lesson} that
 * carried attendance under V25's lesson-as-session model (backfilled by V56,
 * and still written by the deprecated lesson-scoped endpoints). Never
 * reinterpreted as a class session.</li>
 * </ul>
 */
public enum AttendanceSheetSource {

	CLASS_SESSION, LEGACY_LESSON

}
