package com.lms.attendancemanagement.service;

import com.lms.attendancemanagement.domain.AttendanceSheetSource;
import com.lms.attendancemanagement.domain.AttendanceStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Service-level read result composed from {@link
 * com.lms.attendancemanagement.domain.AttendanceRecord} + its sheet - never
 * the JPA entity itself crosses into {@code web}. Shape matches {@code
 * AttendanceRecordResponse}.
 *
 * @param sessionId the LEGACY lesson id (V25 lesson-as-session) - {@code null}
 * for a class-session-sourced record. Kept under its original name for API
 * compatibility.
 * @param classSessionId Wave 8 - the class session this record was taken
 * for; {@code null} for a legacy (LEGACY_LESSON) record.
 * @param classSessionTitle Wave 8 - resolved via {@code ClassSessionLookupApi}
 * at read time; {@code null} for a legacy record.
 */
public record AttendanceRecordView(UUID id, UUID sheetId, AttendanceSheetSource source, UUID courseId,
		UUID classSessionId, String classSessionTitle, UUID sessionId, UUID studentId, AttendanceStatus status,
		UUID markedBy, Instant markedAt, Instant createdAt, Instant updatedAt) {

}
