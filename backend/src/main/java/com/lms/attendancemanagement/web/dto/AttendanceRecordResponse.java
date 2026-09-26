package com.lms.attendancemanagement.web.dto;

import com.lms.attendancemanagement.domain.AttendanceSheetSource;
import com.lms.attendancemanagement.domain.AttendanceStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code { id, courseId, sessionId, studentId, status, markedBy, markedAt, createdAt, updatedAt }} per MVP-016
 * plan §10, extended additively in Wave 8 with {@code sheetId}, {@code source}, {@code classSessionId} and
 * {@code classSessionTitle}.
 *
 * <p>{@code sessionId} keeps its original meaning - the LEGACY lesson id - and is therefore {@code null} for a
 * class-session-sourced record ({@code source = CLASS_SESSION}); {@code classSessionId}/{@code classSessionTitle}
 * are {@code null} for a legacy record ({@code source = LEGACY_LESSON}).
 */
public record AttendanceRecordResponse(UUID id, UUID courseId, UUID sessionId, UUID studentId,
		AttendanceStatus status, UUID markedBy, Instant markedAt, Instant createdAt, Instant updatedAt, UUID sheetId,
		AttendanceSheetSource source, UUID classSessionId, String classSessionTitle) {

}
