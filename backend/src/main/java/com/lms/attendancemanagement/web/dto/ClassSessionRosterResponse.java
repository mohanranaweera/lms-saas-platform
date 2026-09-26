package com.lms.attendancemanagement.web.dto;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/attendance/class-sessions/{classSessionId}/roster} (Wave 8). {@code sheetId} is {@code null}
 * until the first successful mark. {@code markingOpen}/{@code markingClosedReason} mirror the server-side lifecycle
 * gate for the UI only - {@code POST .../records} re-enforces it (409).
 */
public record ClassSessionRosterResponse(UUID sheetId, UUID classSessionId, UUID courseId, String title,
		Instant scheduledStart, Instant scheduledEnd, String sessionStatus, boolean markingOpen,
		String markingClosedReason, List<Entry> roster) {

	/** {@code status} is {@code null} when not yet marked; {@code currentlyEnrolled=false} rows are read-only. */
	public record Entry(UUID studentId, String studentName, AttendanceStatus status, boolean currentlyEnrolled) {
	}

}
