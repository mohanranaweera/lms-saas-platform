package com.lms.enrollmentmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of {@link AccessPolicyApi#evaluateStudentCourseAccess}.
 *
 * @param effectiveExpiresAt the paid window end, extended by any manual
 * extension (null = lifetime or not enrolled).
 * @param accessEndsAt {@code effectiveExpiresAt} plus the grace period - when
 * access actually stops (null = lifetime or not enrolled).
 * @param inGracePeriod true while past {@code effectiveExpiresAt} but before
 * {@code accessEndsAt}.
 */
public record CourseAccessDecision(AccessOutcome outcome, UUID enrollmentId, Instant effectiveExpiresAt,
		Instant accessEndsAt, boolean inGracePeriod, boolean canRequestReactivation) {

	public boolean allowed() {
		return outcome == AccessOutcome.ALLOWED;
	}

}
