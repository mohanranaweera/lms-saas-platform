package com.lms.enrollmentmanagement.api;

/**
 * Whether an enrolled (participating) student has paid access to a course right
 * now - the course roster's access column (ADR-028, plan O-4). Enrollment itself
 * is free; this is display information only and never an authorization input.
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

	NOT_PAID

}
