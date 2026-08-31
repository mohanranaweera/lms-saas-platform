package com.lms.enrollmentmanagement.domain;

/**
 * Mirrors {@code enrollment_expiry_event}'s {@code
 * ck_enrollment_expiry_event_type} CHECK constraint (V22) exactly - a
 * single-value enum at this MVP's scope, mirroring {@link
 * EnrollmentStatus}'s own "don't add values speculatively" precedent. No
 * {@code REMINDER}/{@code GRACE_STARTED} values exist (plan §21) - a future
 * value is a new migration once a real need is ratified.
 */
public enum EnrollmentExpiryEventType {

	EXPIRED

}
