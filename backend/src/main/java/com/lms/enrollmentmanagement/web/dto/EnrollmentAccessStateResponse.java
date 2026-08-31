package com.lms.enrollmentmanagement.web.dto;

import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import java.time.Instant;
import java.util.UUID;

/** {@code GET /api/v1/courses/{courseId}/access-state} response body - never the JPA/domain entity. */
public record EnrollmentAccessStateResponse(EnrollmentAccessStateType state, UUID enrollmentId,
		Instant accessExpiresAt, boolean canRequestReactivation) {

}
