package com.lms.attendancemanagement.service;

import java.util.UUID;

/**
 * Per-row outcome of a single {@link AttendanceMarkCommand} within a batch
 * {@link AttendanceMarkingService#markAttendance} call - the batch-partial
 * marking contract required by plan §13: one invalid row (e.g. a {@code
 * studentId} not on the current roster) never silently fails the whole
 * batch, nor silently succeeds the valid rows without reporting the
 * rejected ones. Exactly one of {@code record}/{@code reason} is non-null,
 * matching {@code success}.
 */
public record AttendanceMarkOutcome(UUID studentId, boolean success, AttendanceRecordView record, String reason) {

	public static AttendanceMarkOutcome success(UUID studentId, AttendanceRecordView record) {
		return new AttendanceMarkOutcome(studentId, true, record, null);
	}

	public static AttendanceMarkOutcome rejected(UUID studentId, String reason) {
		return new AttendanceMarkOutcome(studentId, false, null, reason);
	}

}
