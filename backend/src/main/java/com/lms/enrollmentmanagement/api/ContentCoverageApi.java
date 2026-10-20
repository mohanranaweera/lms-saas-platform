package com.lms.enrollmentmanagement.api;

import java.util.Set;
import java.util.UUID;

/**
 * KLASS-T4c read side for the payment composer (plan §4.4): what the CALLING
 * student already participates in and already has paid coverage for. Tenant
 * from {@code TenantContext}, student from the authenticated principal - no
 * method accepts either.
 */
public interface ContentCoverageApi {

	/**
	 * @param activeCoverageKeys coverage keys of the caller's entitlements for
	 * the course that are not revoked and not past {@code valid_until}.
	 * @param legacyCovered a current legacy enrollment grants course-wide paid
	 * access now (covers every COVERED_BY_CLASS_FEES lesson, never PAID ones).
	 */
	record CallerCoverage(Set<String> activeCoverageKeys, boolean legacyCovered) {

	}

	/**
	 * Courses the caller participates in (free participation, including
	 * backfilled legacy enrollments) plus the source courses linked into them
	 * (D13), in no particular order.
	 */
	Set<UUID> findCallerCourseIds();

	/** Participation in the course, a current legacy enrollment, or participation in a parent linking it. */
	boolean isCallerParticipant(UUID courseId);

	CallerCoverage coverageForCaller(UUID courseId);

	/**
	 * Takes the shared (tenant, caller, coverage) advisory locks - see {@link
	 * CoverageKeys#lockKey} - in sorted order, inside the caller's transaction.
	 */
	void lockCallerCoverage(Set<String> coverageKeys);

}
