package com.lms.enrollmentmanagement.api;

/**
 * The three states {@link EnrollmentAccessApi#resolveAccessState(java.util.UUID, java.util.UUID)}
 * may report for a (student, course) pair - computed live, never stored on
 * {@code enrollment} itself (ADR-013).
 */
public enum EnrollmentAccessStateType {

	/** No {@code enrollment} row (current or superseded) exists for this (student, course) pair. */
	NEVER_ENROLLED,

	/** A current row exists and is not superseded/expired. */
	ACTIVE,

	/** A current row exists but is either superseded or past its {@code accessExpiresAt}. */
	EXPIRED

}
