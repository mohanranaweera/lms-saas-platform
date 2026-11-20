package com.lms.enrollmentmanagement.service;

import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.enrollmentmanagement.api.CoverageKeys;
import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessDecision.Availability;
import com.lms.enrollmentmanagement.api.LessonAccessDecision.Payment;
import com.lms.enrollmentmanagement.api.LessonAccessDecision.RequiredPurchase;
import com.lms.enrollmentmanagement.api.LessonAccessStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The pure, side-effect-free decision of {@link LessonAccessService} (D04/D05):
 * every fact about the student and the course is loaded first (a constant
 * number of queries per course), then each lesson is decided here.
 *
 * <p>Precedence (first match wins): UNAVAILABLE → NOT_ENROLLED →
 * DEVICE_NOT_AUTHORIZED → NOT_STARTED / EXPIRED (lesson window, even when paid -
 * CM-R02) → payment (PAYMENT_PENDING / PAYMENT_REQUIRED) → ALLOWED.
 *
 * <p>Payment coverage:
 * <ul>
 * <li>{@code FREE} lesson - always covered.</li>
 * <li>{@code COVERED_BY_CLASS_FEES} - covered by: a FREE-priced course; legacy
 * course-wide paid access; a MONTHLY entitlement for the lesson's explicit
 * billing month (no billing month = no monthly coverage); a MODULE entitlement
 * for the lesson's module.</li>
 * <li>{@code PAID} - only its own LESSON entitlement (legacy access does NOT
 * cover it).</li>
 * </ul>
 * {@code activeCoverageKeys} must already contain only entitlements that are
 * not revoked and whose {@code valid_until} is null or in the future.
 */
public final class LessonAccessRules {

	private LessonAccessRules() {
	}

	/**
	 * Everything known about one (student, course) pair at evaluation time.
	 *
	 * @param available the course exists in the caller's tenant and is not DRAFT.
	 * @param freePricing the course's pricing model is FREE.
	 * @param oneTimePricing the course is sold as a whole (ONE_TIME) - the
	 * fallback purchase for an uncovered COVERED lesson with no billing month.
	 * @param legacyCovered a current legacy enrollment grants paid access now.
	 * @param modulePrices module id → MODULE offer price ({@code null} = not sold).
	 */
	public record CourseFacts(UUID courseId, boolean available, boolean freePricing, boolean oneTimePricing,
			boolean enrolled, boolean deviceAuthorized, boolean legacyCovered, Set<String> activeCoverageKeys,
			Set<String> pendingCoverageKeys, Map<UUID, ?> modulePrices) {

	}

	public static LessonAccessDecision unavailable(UUID courseId, UUID lessonId) {
		return new LessonAccessDecision(lessonId, courseId, LessonAccessStatus.UNAVAILABLE, Availability.ALWAYS,
				Payment.REQUIRED, null);
	}

	public static LessonAccessDecision decide(CourseFacts facts, LessonPolicy lesson, Instant now) {
		if (lesson == null) {
			return unavailable(facts.courseId(), null);
		}
		Availability availability = availability(lesson, now);
		PaymentOutcome payment = payment(facts, lesson);
		LessonAccessStatus status;
		if (!facts.available()) {
			status = LessonAccessStatus.UNAVAILABLE;
		}
		else if (!facts.enrolled()) {
			status = LessonAccessStatus.NOT_ENROLLED;
		}
		else if (!facts.deviceAuthorized()) {
			status = LessonAccessStatus.DEVICE_NOT_AUTHORIZED;
		}
		else if (availability == Availability.NOT_STARTED) {
			status = LessonAccessStatus.NOT_STARTED;
		}
		else if (availability == Availability.ENDED) {
			status = LessonAccessStatus.EXPIRED;
		}
		else if (payment.payment() == Payment.FREE || payment.payment() == Payment.COVERED) {
			status = LessonAccessStatus.ALLOWED;
		}
		else if (payment.payment() == Payment.PENDING) {
			status = LessonAccessStatus.PAYMENT_PENDING;
		}
		else {
			status = LessonAccessStatus.PAYMENT_REQUIRED;
		}
		return new LessonAccessDecision(lesson.lessonId(), facts.courseId(), status, availability, payment.payment(),
				payment.payment() == Payment.REQUIRED ? payment.purchase() : null);
	}

	static Availability availability(LessonPolicy lesson, Instant now) {
		if (lesson.startsAt() == null && lesson.endsAt() == null) {
			return Availability.ALWAYS;
		}
		if (lesson.startsAt() != null && now.isBefore(lesson.startsAt())) {
			return Availability.NOT_STARTED;
		}
		if (lesson.endsAt() != null && lesson.endsAt().isBefore(now)) {
			return Availability.ENDED;
		}
		return Availability.OPEN;
	}

	record PaymentOutcome(Payment payment, RequiredPurchase purchase) {

	}

	static PaymentOutcome payment(CourseFacts facts, LessonPolicy lesson) {
		LessonPaymentType type = lesson.paymentType() == null ? LessonPaymentType.COVERED_BY_CLASS_FEES
				: lesson.paymentType();
		Set<String> active = facts.activeCoverageKeys();
		Set<String> pending = facts.pendingCoverageKeys();
		return switch (type) {
			case FREE -> new PaymentOutcome(Payment.FREE, null);
			case PAID -> {
				String key = CoverageKeys.lesson(lesson.lessonId());
				if (active.contains(key)) {
					yield new PaymentOutcome(Payment.COVERED, null);
				}
				if (pending.contains(key)) {
					yield new PaymentOutcome(Payment.PENDING, null);
				}
				yield new PaymentOutcome(Payment.REQUIRED, new RequiredPurchase("LESSON", facts.courseId(),
						lesson.moduleId(), lesson.lessonId(), null, null));
			}
			case COVERED_BY_CLASS_FEES -> coveredByClassFees(facts, lesson, active, pending);
		};
	}

	private static PaymentOutcome coveredByClassFees(CourseFacts facts, LessonPolicy lesson, Set<String> active,
			Set<String> pending) {
		if (facts.freePricing() || facts.legacyCovered()) {
			return new PaymentOutcome(Payment.COVERED, null);
		}
		boolean hasMonth = lesson.billingYear() != null && lesson.billingMonth() != null;
		String monthlyKey = hasMonth
				? CoverageKeys.monthly(facts.courseId(), lesson.billingYear(), lesson.billingMonth()) : null;
		String moduleKey = CoverageKeys.module(lesson.moduleId());
		if ((monthlyKey != null && active.contains(monthlyKey)) || active.contains(moduleKey)) {
			return new PaymentOutcome(Payment.COVERED, null);
		}
		if ((monthlyKey != null && pending.contains(monthlyKey)) || pending.contains(moduleKey)) {
			return new PaymentOutcome(Payment.PENDING, null);
		}
		RequiredPurchase purchase;
		if (hasMonth) {
			purchase = new RequiredPurchase("MONTHLY", facts.courseId(), null, null, lesson.billingYear(),
					lesson.billingMonth());
		}
		else if (facts.modulePrices() != null && facts.modulePrices().get(lesson.moduleId()) != null) {
			purchase = new RequiredPurchase("MODULE", facts.courseId(), lesson.moduleId(), null, null, null);
		}
		else if (facts.oneTimePricing()) {
			purchase = new RequiredPurchase("COURSE", facts.courseId(), null, null, null, null);
		}
		else {
			purchase = null;
		}
		return new PaymentOutcome(Payment.REQUIRED, purchase);
	}

}
