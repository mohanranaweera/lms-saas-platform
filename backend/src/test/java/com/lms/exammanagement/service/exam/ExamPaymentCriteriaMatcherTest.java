package com.lms.exammanagement.service.exam;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.exammanagement.service.exam.ExamPaymentCriteriaMatcher.OrderFacts;
import com.lms.exammanagement.service.exam.ExamViews.PaymentCriteriaView;
import com.lms.paymentmanagement.api.OrderLineSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ADR-032 criteria matching for every kind (MONTHS / MODULES / DATES / INSTALLMENT /
 * FULL_FEE), multi-course orders (only the exam's own course counts) and Free Cards (count for
 * MONTHS, never for INSTALLMENT amounts).
 */
class ExamPaymentCriteriaMatcherTest {

	private static final UUID COURSE = UUID.randomUUID();

	private static final UUID OTHER_COURSE = UUID.randomUUID();

	private static final UUID MODULE = UUID.randomUUID();

	private static final UUID LESSON = UUID.randomUUID();

	private static PaymentCriteriaView months(String... months) {
		return new PaymentCriteriaView("MONTHS", List.of(months), List.of(), List.of(), null);
	}

	private static OrderLineSnapshot line(String scope, UUID course, UUID module, UUID lesson, Integer year,
			Integer month, String total) {
		return new OrderLineSnapshot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, scope, course, module,
				lesson, year, month, "KEY", "label", new BigDecimal(total), BigDecimal.ZERO, new BigDecimal(total), "LKR");
	}

	private static OrderFacts lines(OrderLineSnapshot... lines) {
		return new OrderFacts(List.of(lines), null, Map.of(), null);
	}

	@Test
	void monthsMatchOnlyAMonthlyLineOfTheExamCourseInACriteriaMonth() {
		PaymentCriteriaView criteria = months("2026-10", "2026-11");
		assertThat(ExamPaymentCriteriaMatcher.matches(criteria, COURSE,
				lines(line("MONTHLY", COURSE, null, null, 2026, 11, "1000")))).isTrue();
		assertThat(ExamPaymentCriteriaMatcher.matches(criteria, COURSE,
				lines(line("MONTHLY", COURSE, null, null, 2026, 12, "1000")))).isFalse();
		// Multi-course order: the matching month belongs to another course -> no enrollment.
		assertThat(ExamPaymentCriteriaMatcher.matches(criteria, COURSE,
				lines(line("MONTHLY", OTHER_COURSE, null, null, 2026, 10, "1000"),
						line("MONTHLY", COURSE, null, null, 2026, 9, "1000")))).isFalse();
		// A fully discounted (reward) month is still an entitlement for that month.
		assertThat(ExamPaymentCriteriaMatcher.matches(criteria, COURSE,
				lines(line("MONTHLY", COURSE, null, null, 2026, 10, "0")))).isTrue();
	}

	@Test
	void modulesAndDatesMatchTheirOwnScopes() {
		PaymentCriteriaView modules = new PaymentCriteriaView("MODULES", List.of(), List.of(MODULE), List.of(), null);
		assertThat(ExamPaymentCriteriaMatcher.matches(modules, COURSE,
				lines(line("MODULE", COURSE, MODULE, null, null, null, "500")))).isTrue();
		assertThat(ExamPaymentCriteriaMatcher.matches(modules, COURSE,
				lines(line("MODULE", COURSE, UUID.randomUUID(), null, null, null, "500")))).isFalse();

		PaymentCriteriaView dates = new PaymentCriteriaView("DATES", List.of(), List.of(), List.of("2026-10-31"), null);
		OrderFacts onDate = new OrderFacts(List.of(line("LESSON", COURSE, MODULE, LESSON, null, null, "200")), null,
				Map.of(LESSON, LocalDate.parse("2026-10-31")), null);
		OrderFacts otherDate = new OrderFacts(List.of(line("LESSON", COURSE, MODULE, LESSON, null, null, "200")), null,
				Map.of(LESSON, LocalDate.parse("2026-11-01")), null);
		assertThat(ExamPaymentCriteriaMatcher.matches(dates, COURSE, onDate)).isTrue();
		assertThat(ExamPaymentCriteriaMatcher.matches(dates, COURSE, otherDate)).isFalse();
	}

	@Test
	void installmentNeedsTheCumulativeCashThresholdAndAPaymentTowardsTheCourse() {
		PaymentCriteriaView installment = new PaymentCriteriaView("INSTALLMENT", List.of(), List.of(), List.of(),
				new BigDecimal("5000.00"));
		List<OrderLineSnapshot> courseLine = List.of(line("MONTHLY", COURSE, null, null, 2026, 10, "2500"));
		assertThat(ExamPaymentCriteriaMatcher.matches(installment, COURSE,
				new OrderFacts(courseLine, null, Map.of(), new BigDecimal("4999.99")))).isFalse();
		assertThat(ExamPaymentCriteriaMatcher.matches(installment, COURSE,
				new OrderFacts(courseLine, null, Map.of(), new BigDecimal("5000.00")))).isTrue();
		// Enough cash overall, but this payment is for another course only.
		assertThat(ExamPaymentCriteriaMatcher.matches(installment, COURSE,
				new OrderFacts(List.of(line("MONTHLY", OTHER_COURSE, null, null, 2026, 10, "9000")), null, Map.of(),
						new BigDecimal("9000")))).isFalse();
		// A legacy single-course order counts towards its course.
		assertThat(ExamPaymentCriteriaMatcher.matches(installment, COURSE,
				new OrderFacts(List.of(), COURSE, Map.of(), new BigDecimal("6000")))).isTrue();
	}

	@Test
	void fullFeeMatchesTheLegacyFullCourseOrderOfTheExamCourseOnly() {
		PaymentCriteriaView fullFee = new PaymentCriteriaView("FULL_FEE", List.of(), List.of(), List.of(), null);
		assertThat(ExamPaymentCriteriaMatcher.matches(fullFee, COURSE, new OrderFacts(List.of(), COURSE, Map.of(), null)))
			.isTrue();
		assertThat(ExamPaymentCriteriaMatcher.matches(fullFee, COURSE,
				new OrderFacts(List.of(), OTHER_COURSE, Map.of(), null))).isFalse();
		assertThat(ExamPaymentCriteriaMatcher.matches(fullFee, COURSE,
				lines(line("MONTHLY", COURSE, null, null, 2026, 10, "1000")))).isFalse();
	}

	@Test
	void freeCardsQualifyForMonthsButNeverForInstallmentAmounts() {
		PaymentCriteriaView criteria = months("2026-10");
		assertThat(ExamPaymentCriteriaMatcher.freeCardCovers(criteria, LocalDate.parse("2026-09-15"),
				LocalDate.parse("2026-10-01"), false)).isTrue();
		assertThat(ExamPaymentCriteriaMatcher.freeCardCovers(criteria, LocalDate.parse("2026-11-01"),
				LocalDate.parse("2026-12-31"), false)).isFalse();
		assertThat(ExamPaymentCriteriaMatcher.freeCardCovers(criteria, LocalDate.parse("2026-10-01"),
				LocalDate.parse("2026-10-31"), true)).as("revoked").isFalse();
		PaymentCriteriaView installment = new PaymentCriteriaView("INSTALLMENT", List.of(), List.of(), List.of(),
				new BigDecimal("1"));
		assertThat(ExamPaymentCriteriaMatcher.freeCardCovers(installment, LocalDate.parse("2026-10-01"),
				LocalDate.parse("2026-10-31"), false)).isFalse();
	}

}
