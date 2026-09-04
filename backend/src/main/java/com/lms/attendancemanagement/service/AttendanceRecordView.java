package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Service-level read result composed from {@link
 * com.lms.attendancemanagement.domain.AttendanceRecord} - never the JPA
 * entity itself crosses into {@code web}. Shape matches {@code
 * AttendanceRecordResponse} per plan §10.
 */
public record AttendanceRecordView(UUID id, UUID courseId, UUID sessionId, UUID studentId, AttendanceStatus status,
		UUID markedBy, Instant markedAt, Instant createdAt, Instant updatedAt) {

}
