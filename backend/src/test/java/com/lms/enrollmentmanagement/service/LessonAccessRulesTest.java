package com.lms.enrollmentmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonPolicy;
import com.lms.coursemanagement.api.LessonResourceType;
import com.lms.enrollmentmanagement.api.CoverageKeys;
import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessDecision.Availability;
import com.lms.enrollmentmanagement.api.LessonAccessDecision.Payment;
import com.lms.enrollmentmanagement.api.LessonAccessStatus;
import com.lms.enrollmentmanagement.service.LessonAccessRules.CourseFacts;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * KLASS-T4b: the lesson access evaluator matrix (D04/D05, ADR-028) - FREE /
 * COVERED / PAID lessons × legacy / monthly / module / lesson coverage ×
 * pending × lesson window, and the precedence order.
 */
class LessonAccessRulesTest {

	private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

	private static final UUID COURSE = UUID.randomUUID();

	private static final UUID MODULE = UUID.randomUUID();

	private static final UUID LESSON = UUID.randomUUID();

	private static LessonPolicy lesson(LessonPaymentType type, Integer year, Integer month, Instant startsAt,
			Instant endsAt) {
		return new LessonPolicy(LESSON, MODULE, "Module", 1, "Lesson", null, 1, LessonResourceType.DOCUMENT, type,
				type == LessonPaymentType.PAID ? new BigDecimal("500.00") : null, startsAt, endsAt, year, month);
	}

	private static LessonPolicy lesson(LessonPaymentType type) {
		return lesson(type, null, null, null, null);
	}

	private static LessonPolicy monthly(int year, int month) {
		return lesson(LessonPaymentType.COVERED_BY_CLASS_FEES, year, month, null, null);
	}

	private static CourseFacts facts(boolean legacy, Set<String> active, Set<String> pending) {
		return new CourseFacts(COURSE, true, false, false, true, true, legacy, active, pending, Map.of());
	}

	private static LessonAccessDecision decide(CourseFacts facts, LessonPolicy lesson) {
		return LessonAccessRules.decide(facts, lesson, NOW);
	}

	@Nested
	class FreeLessons {

		@Test
		void freeLessonIsAllowedWithoutAnyPayment() {
			LessonAccessDecision decision = decide(facts(false, Set.of(), Set.of()), lesson(LessonPaymentType.FREE));
			assertThat(decision.status()).isEqualTo(LessonAccessStatus.ALLOWED);
			assertThat(decision.payment()).isEqualTo(Payment.FREE);
			assertThat(decision.requiredPurchase()).isNull();
		}

		@Test
		void freeLessonStillNeedsEnrollment() {
			CourseFacts notEnrolled = new CourseFacts(COURSE, true, false, false, false, true, false, Set.of(),
					Set.of(), Map.of());
			assertThat(decide(notEnrolled, lesson(LessonPaymentType.FREE)).status())
				.isEqualTo(LessonAccessStatus.NOT_ENROLLED);
		}

	}

	@Nested
	class CoveredLessons {

		@Test
		void legacyCourseAccessCoversAClassFeeLesson() {
			assertThat(decide(facts(true, Set.of(), Set.of()), monthly(2026, 10)).status())
				.isEqualTo(LessonAccessStatus.ALLOWED);
			assertThat(decide(facts(true, Set.of(), Set.of()), lesson(LessonPaymentType.COVERED_BY_CLASS_FEES))
				.status()).isEqualTo(LessonAccessStatus.ALLOWED);
		}

		@Test
		void freePricedCourseCoversEveryClassFeeLesson() {
			CourseFacts free = new CourseFacts(COURSE, true, true, false, true, true, false, Set.of(), Set.of(),
					Map.of());
			assertThat(decide(free, monthly(2026, 10)).status()).isEqualTo(LessonAccessStatus.ALLOWED);
			assertThat(decide(free, lesson(LessonPaymentType.COVERED_BY_CLASS_FEES)).payment())
				.isEqualTo(Payment.COVERED);
		}

		@Test
		void monthlyEntitlementForTheLessonsOwnBillingMonthCoversIt() {
			Set<String> active = Set.of(CoverageKeys.monthly(COURSE, 2026, 10));
			assertThat(decide(facts(false, active, Set.of()), monthly(2026, 10)).status())
				.isEqualTo(LessonAccessStatus.ALLOWED);
		}

		@Test
		void monthlyEntitlementForAnotherMonthDoesNotCoverIt() {
			Set<String> active = Set.of(CoverageKeys.monthly(COURSE, 2026, 9));
			LessonAccessDecision decision = decide(facts(false, active, Set.of()), monthly(2026, 10));
			assertThat(decision.status()).isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);
			assertThat(decision.requiredPurchase().scope()).isEqualTo("MONTHLY");
			assertThat(decision.requiredPurchase().courseId()).isEqualTo(COURSE);
			assertThat(decision.requiredPurchase().year()).isEqualTo(2026);
			assertThat(decision.requiredPurchase().month()).isEqualTo(10);
		}

		@Test
		void monthlyEntitlementOfAnotherCourseDoesNotCoverIt() {
			Set<String> active = Set.of(CoverageKeys.monthly(UUID.randomUUID(), 2026, 10));
			assertThat(decide(facts(false, active, Set.of()), monthly(2026, 10)).status())
				.isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);
		}

		@Test
		void moduleEntitlementCoversTheModulesClassFeeLessons() {
			Set<String> active = Set.of(CoverageKeys.module(MODULE));
			assertThat(decide(facts(false, active, Set.of()), monthly(2026, 10)).status())
				.isEqualTo(LessonAccessStatus.ALLOWED);
			assertThat(decide(facts(false, active, Set.of()), lesson(LessonPaymentType.COVERED_BY_CLASS_FEES))
				.status()).isEqualTo(LessonAccessStatus.ALLOWED);
		}

		@Test
		void lessonWithoutBillingMonthIsNeverCoveredByAMonthlyEntitlement() {
			Set<String> active = Set.of(CoverageKeys.monthly(COURSE, 2026, 10));
			LessonAccessDecision decision = decide(facts(false, active, Set.of()),
					lesson(LessonPaymentType.COVERED_BY_CLASS_FEES));
			assertThat(decision.status()).isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);
			assertThat(decision.requiredPurchase()).isNull();
		}

		@Test
		void lessonWithoutBillingMonthOffersModuleThenCoursePurchase() {
			CourseFacts moduleSold = new CourseFacts(COURSE, true, false, true, true, true, false, Set.of(), Set.of(),
					Map.of(MODULE, new BigDecimal("1500.00")));
			assertThat(decide(moduleSold, lesson(LessonPaymentType.COVERED_BY_CLASS_FEES)).requiredPurchase().scope())
				.isEqualTo("MODULE");
			CourseFacts oneTime = new CourseFacts(COURSE, true, false, true, true, true, false, Set.of(), Set.of(),
					Map.of());
			assertThat(decide(oneTime, lesson(LessonPaymentType.COVERED_BY_CLASS_FEES)).requiredPurchase().scope())
				.isEqualTo("COURSE");
		}

		@Test
		void pendingMonthlyOrderShowsPaymentPending() {
			Set<String> pending = Set.of(CoverageKeys.monthly(COURSE, 2026, 10));
			LessonAccessDecision decision = decide(facts(false, Set.of(), pending), monthly(2026, 10));
			assertThat(decision.status()).isEqualTo(LessonAccessStatus.PAYMENT_PENDING);
			assertThat(decision.payment()).isEqualTo(Payment.PENDING);
			assertThat(decision.requiredPurchase()).isNull();
		}

		@Test
		void activeCoverageWinsOverAPendingOrder() {
			String key = CoverageKeys.monthly(COURSE, 2026, 10);
			assertThat(decide(facts(false, Set.of(key), Set.of(key)), monthly(2026, 10)).status())
				.isEqualTo(LessonAccessStatus.ALLOWED);
		}

	}

	@Nested
	class PaidLessons {

		@Test
		void paidLessonNeedsItsOwnLessonEntitlement() {
			Set<String> active = Set.of(CoverageKeys.lesson(LESSON));
			assertThat(decide(facts(false, active, Set.of()), lesson(LessonPaymentType.PAID)).status())
				.isEqualTo(LessonAccessStatus.ALLOWED);
		}

		@Test
		void legacyCourseAccessDoesNotCoverAPaidLesson() {
			LessonAccessDecision decision = decide(facts(true, Set.of(), Set.of()), lesson(LessonPaymentType.PAID));
			assertThat(decision.status()).isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);
			assertThat(decision.requiredPurchase().scope()).isEqualTo("LESSON");
			assertThat(decision.requiredPurchase().lessonId()).isEqualTo(LESSON);
			assertThat(decision.requiredPurchase().moduleId()).isEqualTo(MODULE);
		}

		@Test
		void monthlyOrModuleCoverageDoesNotCoverAPaidLesson() {
			Set<String> active = Set.of(CoverageKeys.module(MODULE), CoverageKeys.monthly(COURSE, 2026, 10));
			assertThat(decide(facts(false, active, Set.of()), lesson(LessonPaymentType.PAID, 2026, 10, null, null))
				.status()).isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);
		}

		@Test
		void freePricedCourseDoesNotCoverAPaidLesson() {
			CourseFacts free = new CourseFacts(COURSE, true, true, false, true, true, false, Set.of(), Set.of(),
					Map.of());
			assertThat(decide(free, lesson(LessonPaymentType.PAID)).status())
				.isEqualTo(LessonAccessStatus.PAYMENT_REQUIRED);
		}

		@Test
		void pendingLessonOrderShowsPaymentPending() {
			assertThat(decide(facts(false, Set.of(), Set.of(CoverageKeys.lesson(LESSON))),
					lesson(LessonPaymentType.PAID)).status())
				.isEqualTo(LessonAccessStatus.PAYMENT_PENDING);
		}

	}

	@Nested
	class LessonWindow {

		@Test
		void notStartedLessonIsNotStartedEvenWhenFree() {
			LessonAccessDecision decision = decide(facts(true, Set.of(), Set.of()), lesson(LessonPaymentType.FREE,
					null, null, NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2))));
			assertThat(decision.status()).isEqualTo(LessonAccessStatus.NOT_STARTED);
			assertThat(decision.availability()).isEqualTo(Availability.NOT_STARTED);
		}

		@Test
		void endedLessonIsExpiredEvenWhenPaid() {
			Set<String> active = Set.of(CoverageKeys.lesson(LESSON));
			LessonAccessDecision decision = decide(facts(false, active, Set.of()), lesson(LessonPaymentType.PAID,
					null, null, NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1))));
			assertThat(decision.status()).isEqualTo(LessonAccessStatus.EXPIRED);
			assertThat(decision.availability()).isEqualTo(Availability.ENDED);
			assertThat(decision.payment()).isEqualTo(Payment.COVERED);
		}

		@Test
		void openWindowAndAlwaysAvailability() {
			assertThat(decide(facts(true, Set.of(), Set.of()), lesson(LessonPaymentType.FREE, null, null,
					NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)))).availability())
				.isEqualTo(Availability.OPEN);
			assertThat(decide(facts(true, Set.of(), Set.of()), lesson(LessonPaymentType.FREE)).availability())
				.isEqualTo(Availability.ALWAYS);
		}

	}

	@Nested
	class Precedence {

		@Test
		void unavailableBeatsEverything() {
			CourseFacts unavailable = new CourseFacts(COURSE, false, false, false, false, false, false, Set.of(),
					Set.of(), Map.of());
			assertThat(decide(unavailable, lesson(LessonPaymentType.FREE)).status())
				.isEqualTo(LessonAccessStatus.UNAVAILABLE);
			assertThat(LessonAccessRules.decide(facts(true, Set.of(), Set.of()), null, NOW).status())
				.isEqualTo(LessonAccessStatus.UNAVAILABLE);
		}

		@Test
		void notEnrolledBeatsDeviceWindowAndPayment() {
			CourseFacts notEnrolled = new CourseFacts(COURSE, true, false, false, false, false, false, Set.of(),
					Set.of(), Map.of());
			assertThat(decide(notEnrolled, lesson(LessonPaymentType.PAID, null, null, NOW.plusSeconds(60), null))
				.status()).isEqualTo(LessonAccessStatus.NOT_ENROLLED);
		}

		@Test
		void deviceBeatsWindowAndPaymentIncludingEntitlementBasedAccess() {
			CourseFacts badDevice = new CourseFacts(COURSE, true, false, false, true, false, false,
					Set.of(CoverageKeys.lesson(LESSON)), Set.of(), Map.of());
			assertThat(decide(badDevice, lesson(LessonPaymentType.PAID)).status())
				.isEqualTo(LessonAccessStatus.DEVICE_NOT_AUTHORIZED);
			assertThat(decide(badDevice, lesson(LessonPaymentType.FREE, null, null, NOW.plusSeconds(60), null))
				.status()).isEqualTo(LessonAccessStatus.DEVICE_NOT_AUTHORIZED);
		}

		@Test
		void windowBeatsPayment() {
			assertThat(decide(facts(false, Set.of(), Set.of()), lesson(LessonPaymentType.PAID, null, null,
					NOW.plusSeconds(60), null)).status())
				.isEqualTo(LessonAccessStatus.NOT_STARTED);
		}

	}

}
