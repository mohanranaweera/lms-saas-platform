package com.lms.attendancemanagement.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * {@code POST /api/v1/attendance/sessions/{sessionId}/records} request body
 * (plan §10). {@code courseId} is deliberately absent here - it is always
 * derived server-side from the resolved lesson's owning course, never
 * accepted from the client (plan §12). {@code marks} is capped at 500
 * entries - well beyond any realistic single class-session roster - so a
 * single request cannot hold the {@code @Transactional}
 * {@code AttendanceMarkingService.markAttendance} transaction open for an
 * unbounded time and degrade the shared connection pool for other tenants.
 */
public record MarkAttendanceRequest(
		@NotEmpty @Size(max = 500) List<@Valid AttendanceMarkEntryRequest> marks) {

}
