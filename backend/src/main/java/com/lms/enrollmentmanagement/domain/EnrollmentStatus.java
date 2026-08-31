package com.lms.enrollmentmanagement.domain;

/**
 * Mirrors {@code enrollment}'s {@code ck_enrollment_status} CHECK constraint
 * (V19) exactly - a single-value enum, and intentionally staying that way
 * permanently, not just at an early implementation stage. {@code
 * EnrollmentStatus} answers only "was this row's activation valid" - it is
 * NOT "is access currently live," and it never grows an {@code EXPIRED}/
 * {@code REACTIVATED} value to try to answer that second question.
 *
 * <p>Course-level expiry ({@code ENR-2}) and reactivation ({@code ENR-3})
 * ARE implemented (MVP-012/ADR-013) - access currency is deliberately never
 * stored as an enum value on this column at all. Per ADR-013 ("Enrollment
 * lineage-row model"), it is instead always COMPUTED LIVE from {@code
 * Enrollment#isCurrentlyActive(Instant)} ({@code supersededAt IS NULL AND
 * (accessExpiresAt IS NULL OR accessExpiresAt > now())}), since a Postgres
 * {@code CHECK} constraint cannot reference {@code now()} and a background
 * job to keep a stored status in sync would be more bug-prone than a single,
 * shared, unit-tested live computation. Reactivation is modeled as a NEW
 * {@code enrollment} row (see {@link Enrollment}'s own javadoc), never a
 * transition of this enum on the existing row.
 */
public enum EnrollmentStatus {

	ACTIVE

}
