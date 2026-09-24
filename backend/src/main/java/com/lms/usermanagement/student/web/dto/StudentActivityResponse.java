package com.lms.usermanagement.student.web.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One row of {@code GET /api/v1/students/{id}/activity}'s response body (Wave 3). */
public record StudentActivityResponse(UUID id, UUID actorId, String actorDisplayName, String action,
		String reason, Map<String, Object> metadata, Instant occurredAt) {

}
