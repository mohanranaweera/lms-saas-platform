package com.lms.enrollmentmanagement.api;

/**
 * Whether an enrolled (participating) student has access to a course right now -
 * decides who the course roster lists and its access column (ADR-028; owner,
 * 2026-11-10). Display only, never an authorization input.
 */
public enum RosterAccessStatus {

	/**
	 * An open legacy course-wide enrollment window, a MONTHLY entitlement for the
	 * current month (tenant timezone), or an active MODULE/LESSON entitlement.
	 */
	PAID,

	/** No payment, but an effective Free Card covering the current month (ADR-030). */
	FREE_CARD,

	/** The course's pricing model is FREE - nothing to pay. */
	FREE_COURSE,

	/** Enrolled without current access - left off the roster. */
	NOT_PAID

}
