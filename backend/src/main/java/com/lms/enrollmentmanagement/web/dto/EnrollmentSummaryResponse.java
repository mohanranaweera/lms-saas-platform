package com.lms.enrollmentmanagement.web.dto;

import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import java.time.Instant;
import java.util.UUID;

/** One row of {@code GET /api/v1/enrollments/my}'s response body. */
public record EnrollmentSummaryResponse(UUID enrollmentId, UUID courseId, EnrollmentAccessStateType state,
		Instant accessExpiresAt, boolean canRequestReactivation) {

}
