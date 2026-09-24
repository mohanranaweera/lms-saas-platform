package com.lms.enrollmentmanagement.web.dto;

import java.time.Instant;
import java.util.UUID;

/** One row of the Wave 3 {@code GET /api/v1/students/{id}/enrollments} staff read. */
public record EnrollmentHistoryEntryResponse(UUID enrollmentId, UUID courseId, boolean current, Instant activatedAt,
		Instant accessExpiresAt, Instant supersededAt, Instant revokedAt, String revokeReason,
		UUID reactivatedFromEnrollmentId) {

}
