package com.lms.enrollmentmanagement.api;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link LessonAccessApi#requireLessonAccess} for an enrolled student
 * whose lesson is not {@link LessonAccessStatus#ALLOWED} (payment, window).
 * Mapped to {@code 403} with {@code code = <status>} and the full {@code
 * access} decision (plan §4.3); {@link LessonAccessStatus#UNAVAILABLE} is never
 * thrown as this type (it is a 404). An {@link ApplicationException}, so the
 * AOC Player exchange/heartbeat catch it like every other denial.
 */
public class LessonAccessDeniedException extends ApplicationException {

	private final transient LessonAccessDecision decision;

	public LessonAccessDeniedException(LessonAccessDecision decision) {
		super(HttpStatus.FORBIDDEN, decision.status().name(), messageFor(decision.status()));
		this.decision = decision;
	}

	public LessonAccessDecision getDecision() {
		return decision;
	}

	private static String messageFor(LessonAccessStatus status) {
		return switch (status) {
			case NOT_ENROLLED -> "Enroll in this course to open its lessons.";
			case PAYMENT_REQUIRED -> "This lesson needs a payment before it can be opened.";
			case PAYMENT_PENDING -> "Your payment for this lesson is awaiting confirmation.";
			case NOT_STARTED -> "This lesson has not started yet.";
			case EXPIRED -> "This lesson has ended.";
			case DEVICE_NOT_AUTHORIZED ->
				"This course can only be opened on a limited number of devices, and this device is not one of them.";
			case UNAVAILABLE -> "This lesson is not available.";
			case ALLOWED -> "Allowed.";
		};
	}

}
