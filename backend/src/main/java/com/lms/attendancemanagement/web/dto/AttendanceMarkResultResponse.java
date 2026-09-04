package com.lms.attendancemanagement.web.dto;

import java.util.UUID;

/**
 * Per-row outcome of a batch mark call (plan §13's batch-partial marking
 * contract) - exactly one of {@code record}/{@code reason} is non-null,
 * matching {@code success}.
 */
public record AttendanceMarkResultResponse(UUID studentId, boolean success, AttendanceRecordResponse record,
		String reason) {

}
