package com.lms.enrollmentmanagement.api;

import java.util.UUID;

/**
 * {@link LessonAccessApi}'s per-lesson result. Safe metadata only.
 *
 * @param courseId the course the lesson belongs to (for a lesson projected from
 * a linked source course, the SOURCE course - D13).
 * @param availability lesson schedule window, independent of payment.
 * @param payment the payment dimension, independent of the window/enrollment.
 * @param requiredPurchase what would unlock the lesson when {@code payment} is
 * {@link Payment#REQUIRED} (or {@code null} when no purchasable item exists).
 */
public record LessonAccessDecision(UUID lessonId, UUID courseId, LessonAccessStatus status,
		Availability availability, Payment payment, RequiredPurchase requiredPurchase) {

	public boolean allowed() {
		return status == LessonAccessStatus.ALLOWED;
	}

	public enum Availability {

		NOT_STARTED, OPEN, ENDED, ALWAYS

	}

	public enum Payment {

		FREE, COVERED, PENDING, REQUIRED

	}

	/**
	 * @param scope {@code MONTHLY}, {@code MODULE}, {@code LESSON} or {@code
	 * COURSE} (the existing whole-course order flow).
	 */
	public record RequiredPurchase(String scope, UUID courseId, UUID moduleId, UUID lessonId, Integer year,
			Integer month) {

	}

}
