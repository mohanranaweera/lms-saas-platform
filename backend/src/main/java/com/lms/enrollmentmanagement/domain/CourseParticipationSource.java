package com.lms.enrollmentmanagement.domain;

/**
 * {@code course_participation.source} (V86, D01, ADR-028): how a free
 * participation row came to exist. Informational only - every source grants
 * the same thing (course navigation), never paid access.
 */
public enum CourseParticipationSource {

	/** The student joined through {@code POST /api/v1/enrollments/my/courses/{courseId}}. */
	SELF_ENROLL,

	/** Backfilled by V86 from a pre-existing (non-revoked) enrollment lineage. */
	LEGACY_ENROLLMENT,

	/** Upserted by a verified payment confirmation / approved slip activation or reactivation. */
	PAID_ACTIVATION,

	/** Upserted by a staff grant (ADR-016, {@code fromApprovedManualEvidence}). */
	STAFF_GRANT

}
