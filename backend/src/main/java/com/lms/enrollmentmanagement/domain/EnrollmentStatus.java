package com.lms.enrollmentmanagement.domain;

/**
 * Mirrors {@code enrollment}'s {@code ck_enrollment_status} CHECK constraint
 * (V19) exactly - a single-value enum at this minimal-slice stage.
 * Course-level expiry ({@code ENR-2}) and reactivation ({@code ENR-3}) are
 * Module 12's job, out of this module's scope - do not add
 * {@code EXPIRED}/{@code REACTIVATED} values speculatively.
 */
public enum EnrollmentStatus {

	ACTIVE

}
