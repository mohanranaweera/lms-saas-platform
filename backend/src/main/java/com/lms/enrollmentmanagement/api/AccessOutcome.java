package com.lms.enrollmentmanagement.api;

/** The single answer {@link AccessPolicyApi#evaluateStudentCourseAccess} gives for a (student, course) pair. */
public enum AccessOutcome {

	/** Entitled, inside the (possibly extended, possibly grace) window, on an authorized device. */
	ALLOWED,

	/** No current enrollment exists. */
	NOT_ENROLLED,

	/** A current enrollment exists but its window (extension and grace included) has ended, or it was revoked. */
	EXPIRED,

	/** Entitled, but the current device is not one the course-level device limit authorizes. */
	DEVICE_NOT_AUTHORIZED

}
