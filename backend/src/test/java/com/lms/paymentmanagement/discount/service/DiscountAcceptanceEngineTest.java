package com.lms.paymentmanagement.discount.service;

import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.ART;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.MATHS;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.NOW;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.SCIENCE;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.lesson;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.monthly;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.rule;
import static org.assertj.core.api.Assertions.assertThat;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.DiscountValueType;
import com.lms.paymentmanagement.discount.domain.SelectionMode;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedDiscount;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import com.lms.paymentmanagement.discount.service.DiscountPricing.DiscountOutcome;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardPlan;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * DISC-01 acceptance gaps at engine level (complements {@link
 * DiscountPricingServiceTest} / {@link DiscountAllocatorTest} without repeating
 * them): A4 selection modes over advance lines, A5 ALL-of-2 with one course,
 * A11/A12 odd percentages, capped fixed amounts and a randomized
 * no-over-discount / no-stacking property over several rules.
 */
class DiscountAcceptanceEngineTest {

	private final DiscountPricingService service = new DiscountPricingService();

	private DiscountOutcome apply(List<CartLine> lines, List<RuleSnapshot> rules) {
		return service.applyDiscounts(lines, RewardPlan.EMPTY, rules, null, NOW);
	}

	private static BigDecimal sum(Map<Integer, BigDecimal> allocation) {
		return allocation.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	// ------------------------------------------------------------------
	// A4: advance boundary with ALL / MIN_COUNT.
	// ------------------------------------------------------------------

	@Test
	void a4_advanceMinCountCountsDistinctCoursesWithAnAdvanceLineAndNeverDiscountsTheCurrentMonth() {
		RuleSnapshot advance = rule(DiscountType.ADVANCE_PAYMENT, SelectionMode.MIN_COUNT, 2,
				DiscountValueType.PERCENTAGE, "10", List.of(MATHS, SCIENCE, ART));
		// Two advance months of Maths + a current-month Science line = only ONE course with an advance line.
		List<CartLine> oneAdvanceCourse = List.of(monthly(1, MATHS, 2026, 11, "1000.00"),
				monthly(2, MATHS, 2026, 12, "1000.00"), monthly(3, SCIENCE, 2026, 10, "800.00"));
		assertThat(apply(oneAdvanceCourse, List.of(advance)).applied()).isEmpty();

		// Art in November makes it two courses; the current-month Science line stays undiscounted.
		List<CartLine> twoAdvanceCourses = new ArrayList<>(oneAdvanceCourse);
		twoAdvanceCourses.add(monthly(4, ART, 2026, 11, "500.00"));
		DiscountOutcome outcome = apply(twoAdvanceCourses, List.of(advance));
		assertThat(outcome.applied()).hasSize(1);
		AppliedDiscount applied = outcome.applied().get(0);
		assertThat(applied.lineNos()).containsExactly(1, 2, 4);
		assertThat(applied.amount()).isEqualByComparingTo("250.00");
		assertThat(applied.qualification().qualifyingCourseIds()).containsExactlyInAnyOrder(MATHS, ART);
		assertThat(applied.qualification().advancePeriods()).containsExactly("2026-11", "2026-12");
	}

	@Test
	void a4_advanceAllNeedsAnAdvanceLineInEveryRequiredCourseAndIgnoresNonMonthlyLines() {
		RuleSnapshot advance = rule(DiscountType.ADVANCE_PAYMENT, SelectionMode.ALL, null, DiscountValueType.FIXED,
				"300", List.of(MATHS, SCIENCE));
		// Science is present only as a LESSON line - never an advance line.
		assertThat(apply(List.of(monthly(1, MATHS, 2026, 11, "1000.00"), lesson(2, SCIENCE, "1000.00")),
				List.of(advance))
			.applied()).isEmpty();
		// Science only in a past month - not advance either.
		assertThat(apply(List.of(monthly(1, MATHS, 2026, 11, "1000.00"), monthly(2, SCIENCE, 2026, 9, "1000.00")),
				List.of(advance))
			.applied()).isEmpty();
		// Both in the advance window -> applies, only to the advance lines.
		DiscountOutcome outcome = apply(List.of(monthly(1, MATHS, 2026, 11, "1000.00"),
				monthly(2, SCIENCE, 2027, 1, "1000.00"), monthly(3, SCIENCE, 2026, 10, "1000.00")), List.of(advance));
		assertThat(outcome.applied().get(0).lineNos()).containsExactly(1, 2);
		assertThat(outcome.total()).isEqualByComparingTo("300.00");
	}

	// ------------------------------------------------------------------
	// A5: two months of one course are one course.
	// ------------------------------------------------------------------

	@Test
	void a5_twoMonthsOfOneCourseDoNotSatisfyAnAllOfTwoBundle() {
		RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ALL, null, DiscountValueType.FIXED,
				"500", List.of(MATHS, SCIENCE));

		assertThat(apply(List.of(monthly(1, MATHS, 2026, 10, "1000.00"), monthly(2, MATHS, 2026, 11, "1000.00"),
				monthly(3, MATHS, 2026, 12, "1000.00")), List.of(bundle))
			.applied()).isEmpty();
		assertThat(apply(List.of(monthly(1, MATHS, 2026, 10, "1000.00"), monthly(2, SCIENCE, 2026, 10, "1000.00")),
				List.of(bundle))
			.total()).isEqualByComparingTo("500.00");
	}

	// ------------------------------------------------------------------
	// A11 / A12: rounding, caps, no negative lines.
	// ------------------------------------------------------------------

	@Test
	void a11_oddPercentageOverThreeLinesSumsExactlyToTheRoundedTotal() {
		RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
				DiscountValueType.PERCENTAGE, "33.33", List.of(MATHS));
		List<CartLine> lines = List.of(lesson(1, MATHS, "333.33"), lesson(2, MATHS, "333.33"),
				lesson(3, MATHS, "333.34"));

		AppliedDiscount applied = apply(lines, List.of(bundle)).applied().get(0);

		// 1000.00 x 33.33% = 333.30 (exact, HALF_UP).
		assertThat(applied.amount()).isEqualByComparingTo("333.30");
		assertThat(sum(applied.allocation())).isEqualByComparingTo(applied.amount());
		assertThat(applied.allocation()).hasSize(3);
		for (CartLine line : lines) {
			BigDecimal share = applied.allocation().get(line.lineNo());
			assertThat(share).isPositive().isLessThanOrEqualTo(line.unitAmount());
			assertThat(share.scale()).isEqualTo(2);
		}
		// Same input, same output (deterministic).
		assertThat(apply(lines, List.of(bundle)).applied().get(0).allocation()).isEqualTo(applied.allocation());
	}

	@Test
	void a11_percentageRoundsHalfUpOnTheEligibleTotal() {
		RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
				DiscountValueType.PERCENTAGE, "33.33", List.of(MATHS));
		// 0.15 x 33.33% = 0.049995 -> 0.05; split over 0.05/0.05/0.05 (ties -> lower lineNo first).
		AppliedDiscount applied = apply(
				List.of(lesson(1, MATHS, "0.05"), lesson(2, MATHS, "0.05"), lesson(3, MATHS, "0.05")),
				List.of(bundle))
			.applied()
			.get(0);
		assertThat(applied.amount()).isEqualByComparingTo("0.05");
		assertThat(applied.allocation().get(1)).isEqualByComparingTo("0.02");
		assertThat(applied.allocation().get(2)).isEqualByComparingTo("0.02");
		assertThat(applied.allocation().get(3)).isEqualByComparingTo("0.01");
	}

	@Test
	void a12_fixedAmountAboveTheEligibleTotalIsCappedPerLineAndLeavesOtherCoursesAlone() {
		RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null, DiscountValueType.FIXED,
				"99999.99", List.of(MATHS));
		List<CartLine> lines = List.of(lesson(1, MATHS, "10.01"), lesson(2, MATHS, "20.02"), lesson(3, MATHS, "0.03"),
				lesson(4, ART, "500.00"));

		AppliedDiscount applied = apply(lines, List.of(bundle)).applied().get(0);

		assertThat(applied.amount()).isEqualByComparingTo("30.06");
		assertThat(applied.allocation()).containsOnlyKeys(1, 2, 3);
		assertThat(applied.allocation().get(1)).isEqualByComparingTo("10.01");
		assertThat(applied.allocation().get(2)).isEqualByComparingTo("20.02");
		assertThat(applied.allocation().get(3)).isEqualByComparingTo("0.03");
	}

	/**
	 * A11/A12 property: for random carts and several competing rules, the
	 * applied discounts never touch a line twice, never exceed a line's unit
	 * amount, each allocation sums exactly to its discount, and the total never
	 * exceeds the eligible base.
	 */
	@Test
	void a12_randomizedCombinationsNeverStackNeverOverDiscountAndAlwaysSumExactly() {
		Random random = new Random(20261020L);
		List<UUID> courses = List.of(MATHS, SCIENCE, ART);
		for (int run = 0; run < 300; run++) {
			List<CartLine> lines = new ArrayList<>();
			int count = 1 + random.nextInt(6);
			for (int i = 1; i <= count; i++) {
				UUID course = courses.get(random.nextInt(courses.size()));
				String unit = BigDecimal.valueOf(1 + random.nextInt(300_000), 2).toPlainString();
				lines.add(random.nextBoolean() ? lesson(i, course, unit)
						: monthly(i, course, 2026, 9 + random.nextInt(4), unit));
			}
			List<RuleSnapshot> rules = new ArrayList<>();
			int ruleCount = 1 + random.nextInt(4);
			for (int r = 0; r < ruleCount; r++) {
				DiscountType type = random.nextBoolean() ? DiscountType.BUNDLE_AMOUNT_OFF
						: DiscountType.ADVANCE_PAYMENT;
				boolean percentage = random.nextBoolean();
				String value = percentage ? BigDecimal.valueOf(1 + random.nextInt(10_000), 2).toPlainString()
						: BigDecimal.valueOf(1 + random.nextInt(500_000), 2).toPlainString();
				List<UUID> required = new ArrayList<>(courses.subList(0, 1 + random.nextInt(courses.size())));
				rules.add(rule(type, SelectionMode.ANY, null,
						percentage ? DiscountValueType.PERCENTAGE : DiscountValueType.FIXED, value, required));
			}

			DiscountOutcome outcome = apply(lines, rules);

			Set<Integer> seen = new HashSet<>();
			BigDecimal base = lines.stream().map(CartLine::unitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
			for (AppliedDiscount applied : outcome.applied()) {
				assertThat(sum(applied.allocation())).isEqualByComparingTo(applied.amount());
				for (Map.Entry<Integer, BigDecimal> share : applied.allocation().entrySet()) {
					assertThat(seen.add(share.getKey())).as("line %s discounted twice", share.getKey()).isTrue();
					CartLine line = lines.get(share.getKey() - 1);
					assertThat(share.getValue()).isPositive().isLessThanOrEqualTo(line.unitAmount());
				}
			}
			assertThat(outcome.total()).isLessThanOrEqualTo(base);
		}
	}

}
