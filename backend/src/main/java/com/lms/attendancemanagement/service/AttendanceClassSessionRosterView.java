package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Class-session roster + existing marks (Wave 8 {@code GET
 * /attendance/class-sessions/{id}/roster}).
 *
 * @param sheetId {@code null} until the first successful mark creates the
 * session's sheet - a read never creates one.
 * @param markingOpen the {@link ClassSessionMarkingPolicy} result, mirrored
 * for the UI; the mark endpoint re-enforces it server-side regardless.
 */
public record AttendanceClassSessionRosterView(UUID sheetId, UUID classSessionId, UUID courseId, String title,
		Instant scheduledStart, Instant scheduledEnd, String sessionStatus, boolean markingOpen,
		String markingClosedReason, List<Entry> roster) {

	/**
	 * @param status {@code null} when not yet marked for this session.
	 * @param currentlyEnrolled {@code false} for a student who was marked
	 * earlier but is no longer currently enrolled - shown so historical marks
	 * stay visible, but any new mark for them is rejected per-row.
	 */
	public record Entry(UUID studentId, String studentName, AttendanceStatus status, boolean currentlyEnrolled) {
	}

}
