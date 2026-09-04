package com.lms.attendancemanagement.web.dto;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import java.time.Instant;
import java.util.UUID;

/** {@code { id, courseId, sessionId, studentId, status, markedBy, markedAt, createdAt, updatedAt }} per plan §10. */
public record AttendanceRecordResponse(UUID id, UUID courseId, UUID sessionId, UUID studentId,
		AttendanceStatus status, UUID markedBy, Instant markedAt, Instant createdAt, Instant updatedAt) {

}
