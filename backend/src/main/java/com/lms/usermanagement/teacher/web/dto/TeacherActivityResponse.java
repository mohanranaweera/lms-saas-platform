package com.lms.usermanagement.teacher.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One row of {@code GET /api/v1/teachers/{id}/activity}'s response body (Wave 3). */
public record TeacherActivityResponse(UUID id, UUID actorId, String actorDisplayName, String action, String reason,
		Map<String, Object> metadata, Instant occurredAt) {

}
