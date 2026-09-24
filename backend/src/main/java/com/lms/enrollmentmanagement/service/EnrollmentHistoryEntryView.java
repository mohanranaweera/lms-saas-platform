package com.lms.enrollmentmanagement.service;

import java.time.Instant;
import java.util.UUID;

/**
 * One row of the Wave 3 staff-facing {@code GET
 * /api/v1/students/{id}/enrollments} read - unlike {@link
 * EnrollmentSummaryView} (current-only, student's own "My Courses"), this
 * carries the full lineage/evidence detail (including superseded/revoked
 * rows) a staff member reviewing a student's enrollment history needs.
 */
public record EnrollmentHistoryEntryView(UUID enrollmentId, UUID courseId, boolean current, Instant activatedAt,
		Instant accessExpiresAt, Instant supersededAt, Instant revokedAt, String revokeReason,
		UUID reactivatedFromEnrollmentId) {

}
