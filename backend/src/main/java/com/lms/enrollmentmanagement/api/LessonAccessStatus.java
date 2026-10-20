package com.lms.enrollmentmanagement.api;

/**
 * The single answer of {@link LessonAccessApi} for one lesson (D04, ADR-028).
 * Precedence, first match wins: {@link #UNAVAILABLE} → {@link #NOT_ENROLLED}
 * → {@link #DEVICE_NOT_AUTHORIZED} → {@link #NOT_STARTED}/{@link #EXPIRED}
 * (lesson window) → payment ({@link #PAYMENT_PENDING}/{@link
 * #PAYMENT_REQUIRED}) → {@link #ALLOWED}.
 */
public enum LessonAccessStatus {

	/** Protected resources of this lesson may be opened now. */
	ALLOWED,

	/** No participation (direct, legacy enrollment, or via a linked parent course). */
	NOT_ENROLLED,

	/** Paid coverage is missing. */
	PAYMENT_REQUIRED,

	/** Coverage is missing but an order covering it awaits payment confirmation / slip review. */
	PAYMENT_PENDING,

	/** {@code now < startsAt}. */
	NOT_STARTED,

	/** {@code endsAt < now} - also when paid (CM-R02). */
	EXPIRED,

	/** Lesson/course does not exist in the caller's tenant, does not belong to the course, or the course is DRAFT. */
	UNAVAILABLE,

	/** The current device is not one the course-level device limit authorizes. */
	DEVICE_NOT_AUTHORIZED

}
