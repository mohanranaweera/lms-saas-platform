package com.lms.paymentmanagement.discount.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.DiscountValueType;
import com.lms.paymentmanagement.discount.domain.SelectionMode;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedDiscount;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CodeStatus;
import com.lms.paymentmanagement.discount.service.DiscountPricing.DiscountOutcome;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardPlan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Value evaluators (advance, bundle, code), gating, combination (D6) and rounding (D4). */
class DiscountPricingServiceTest {

	static final YearMonth NOW = YearMonth.of(2026, 10);

	static final UUID MATHS = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	static final UUID SCIENCE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

	static final UUID ART = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	private final DiscountPricingService service = new DiscountPricingService();

	static RuleSnapshot rule(DiscountType type, SelectionMode mode, Integer minCount, DiscountValueType valueType,
			String value, List<UUID> required) {
		return rule(UUID.randomUUID(), type, mode, minCount, valueType, value, required, LocalDate.of(2026, 1, 1),
				null);
	}

	static RuleSnapshot rule(UUID id, DiscountType type, SelectionMode mode, Integer minCount,
			DiscountValueType valueType, String value, List<UUID> required, LocalDate validFrom, String minPurchase) {
		return new RuleSnapshot(id, UUID.randomUUID(), type, type + " rule",
				type == DiscountType.DISCOUNT_CODE ? "SAVE10" : null, mode, minCount, valueType,
				value == null ? null : new BigDecimal(value), null, validFrom, LocalDate.of(2026, 12, 31), null,
				minPurchase == null ? null : new BigDecimal(minPurchase), "LKR", required, List.of());
	}

	static CartLine monthly(int lineNo, UUID course, int year, int month, String unit) {
		return new CartLine(lineNo, "MONTHLY:" + course + ":" + year + "-" + month, course, "MONTHLY", year, month,
				new BigDecimal(unit));
	}

	static CartLine lesson(int lineNo, UUID course, String unit) {
		return new CartLine(lineNo, "LESSON:" + lineNo, course, "LESSON", null, null, new BigDecimal(unit));
	}

	private DiscountOutcome apply(List<CartLine> lines, List<RuleSnapshot> rules, UUID codeRuleId) {
		return service.applyDiscounts(lines, RewardPlan.EMPTY, rules, codeRuleId, NOW);
	}

	@Nested
	class AdvancePayment {

		@Test
		void currentMonthIsNotAdvanceNextMonthIs() {
			RuleSnapshot advance = rule(DiscountType.ADVANCE_PAYMENT, SelectionMode.ANY, null,
					DiscountValueType.PERCENTAGE, "10", List.of(MATHS));
			List<CartLine> lines = List.of(monthly(1, MATHS, 2026, 10, "1000.00"),
					monthly(2, MATHS, 2026, 11, "1000.00"));

			DiscountOutcome outcome = apply(lines, List.of(advance), null);

			assertThat(outcome.applied()).hasSize(1);
			AppliedDiscount applied = outcome.applied().get(0);
			assertThat(applied.lineNos()).containsExactly(2);
			assertThat(applied.amount()).isEqualByComparingTo("100.00");
			assertThat(applied.qualification().advancePeriods()).containsExactly("2026-11");
		}

		@Test
		void onlyCurrentOrPastMonthsDoNotQualify() {
			RuleSnapshot advance = rule(DiscountType.ADVANCE_PAYMENT, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "200", List.of(MATHS));

			DiscountOutcome outcome = apply(
					List.of(monthly(1, MATHS, 2026, 9, "1000.00"), monthly(2, MATHS, 2026, 10, "1000.00")),
					List.of(advance), null);

			assertThat(outcome.applied()).isEmpty();
			assertThat(outcome.total()).isEqualByComparingTo("0");
		}

		@Test
		void selectionCountsRequiredCoursesWithAnAdvanceLine() {
			RuleSnapshot advance = rule(DiscountType.ADVANCE_PAYMENT, SelectionMode.ALL, null,
					DiscountValueType.FIXED, "300", List.of(MATHS, SCIENCE));
			// Science only has a current-month line -> not all required courses are advance.
			List<CartLine> lines = List.of(monthly(1, MATHS, 2026, 11, "1000.00"),
					monthly(2, SCIENCE, 2026, 10, "1000.00"));

			assertThat(apply(lines, List.of(advance), null).applied()).isEmpty();

			List<CartLine> both = List.of(monthly(1, MATHS, 2026, 11, "1000.00"),
					monthly(2, SCIENCE, 2026, 12, "500.00"), monthly(3, SCIENCE, 2026, 10, "500.00"));
			AppliedDiscount applied = apply(both, List.of(advance), null).applied().get(0);
			assertThat(applied.lineNos()).containsExactly(1, 2);
			assertThat(applied.allocation().get(1)).isEqualByComparingTo("200.00");
			assertThat(applied.allocation().get(2)).isEqualByComparingTo("100.00");
		}

	}

	@Nested
	class SelectionModes {

		@Test
		void bundleCountsDistinctCoursesNotLines() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.MIN_COUNT, 2,
					DiscountValueType.FIXED, "500", List.of(MATHS, SCIENCE, ART));
			List<CartLine> twoMonthsOneCourse = List.of(monthly(1, MATHS, 2026, 10, "1000.00"),
					monthly(2, MATHS, 2026, 11, "1000.00"));

			assertThat(apply(twoMonthsOneCourse, List.of(bundle), null).applied()).isEmpty();

			List<CartLine> twoCourses = List.of(monthly(1, MATHS, 2026, 10, "1000.00"),
					lesson(2, SCIENCE, "1000.00"));
			assertThat(apply(twoCourses, List.of(bundle), null).total()).isEqualByComparingTo("500.00");
		}

		@Test
		void allRequiresEveryCourse() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ALL, null,
					DiscountValueType.FIXED, "100", List.of(MATHS, SCIENCE));

			assertThat(apply(List.of(lesson(1, MATHS, "500.00")), List.of(bundle), null).applied()).isEmpty();
			assertThat(apply(List.of(lesson(1, MATHS, "500.00"), lesson(2, SCIENCE, "500.00")), List.of(bundle),
					null)
				.total()).isEqualByComparingTo("100.00");
		}

		@Test
		void anyNeedsOneCourseAndDiscountsOnlyRequiredCourses() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.PERCENTAGE, "50", List.of(MATHS));
			DiscountOutcome outcome = apply(List.of(lesson(1, MATHS, "500.00"), lesson(2, ART, "800.00")),
					List.of(bundle), null);

			assertThat(outcome.applied().get(0).lineNos()).containsExactly(1);
			assertThat(outcome.total()).isEqualByComparingTo("250.00");
		}

		@Test
		void minimumPurchaseUsesTheWholeCourseSubtotal() {
			RuleSnapshot bundle = rule(UUID.randomUUID(), DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "100", List.of(MATHS), LocalDate.of(2026, 1, 1), "1500.00");

			assertThat(apply(List.of(lesson(1, MATHS, "1000.00")), List.of(bundle), null).applied()).isEmpty();
			// Art is not a required course but counts toward the minimum purchase (D8).
			assertThat(apply(List.of(lesson(1, MATHS, "1000.00"), lesson(2, ART, "500.00")), List.of(bundle), null)
				.total()).isEqualByComparingTo("100.00");
		}

	}

	@Nested
	class Amounts {

		@Test
		void fixedIsCappedAtTheEligibleTotal() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "5000", List.of(MATHS));

			assertThat(apply(List.of(lesson(1, MATHS, "1200.00")), List.of(bundle), null).total())
				.isEqualByComparingTo("1200.00");
		}

		@Test
		void percentageRoundsHalfUpThenAllocatesExactly() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.PERCENTAGE, "12.5", List.of(MATHS));
			// 3 x 33.33 = 99.99 -> 12.49875 -> 12.50
			DiscountOutcome outcome = apply(
					List.of(lesson(1, MATHS, "33.33"), lesson(2, MATHS, "33.33"), lesson(3, MATHS, "33.33")),
					List.of(bundle), null);

			AppliedDiscount applied = outcome.applied().get(0);
			assertThat(applied.amount()).isEqualByComparingTo("12.50");
			assertThat(applied.allocation().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
				.isEqualByComparingTo("12.50");
			assertThat(applied.allocation().get(1)).isEqualByComparingTo("4.17");
			assertThat(applied.allocation().get(2)).isEqualByComparingTo("4.17");
			assertThat(applied.allocation().get(3)).isEqualByComparingTo("4.16");
		}

		@Test
		void hundredPercentNeverExceedsTheUnitAmounts() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.PERCENTAGE, "100", List.of(MATHS));

			DiscountOutcome outcome = apply(List.of(lesson(1, MATHS, "10.01"), lesson(2, MATHS, "0.99")),
					List.of(bundle), null);

			assertThat(outcome.total()).isEqualByComparingTo("11.00");
			assertThat(outcome.applied().get(0).allocation().get(1)).isEqualByComparingTo("10.01");
		}

	}

	/** D4a (owner decision 2026-10-21): FIXED bundle / code amounts count once per qualifying billing month. */
	@Nested
	class PerBillingMonth {

		@Test
		void fixedBundleAppliesOncePerMonthOfTheCourse() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "200", List.of(MATHS));

			// The reported case: Sep + Oct of one course -> 200 each month, not 200 for the order.
			DiscountOutcome outcome = apply(List.of(monthly(1, MATHS, 2026, 9, "3500.00"),
					monthly(2, MATHS, 2026, 10, "3500.00")), List.of(bundle), null);

			AppliedDiscount applied = outcome.applied().get(0);
			assertThat(applied.amount()).isEqualByComparingTo("400.00");
			assertThat(applied.allocation().get(1)).isEqualByComparingTo("200.00");
			assertThat(applied.allocation().get(2)).isEqualByComparingTo("200.00");
		}

		@Test
		void fixedCodeAppliesOncePerMonthToo() {
			RuleSnapshot code = rule(DiscountType.DISCOUNT_CODE, SelectionMode.ANY, null, DiscountValueType.FIXED,
					"150", List.of(MATHS));

			DiscountOutcome outcome = apply(List.of(monthly(1, MATHS, 2026, 9, "1000.00"),
					monthly(2, MATHS, 2026, 10, "1000.00"), monthly(3, MATHS, 2026, 11, "1000.00")), List.of(code),
					code.ruleId());

			assertThat(outcome.codeStatus()).isEqualTo(CodeStatus.APPLIED);
			assertThat(outcome.total()).isEqualByComparingTo("450.00");
		}

		@Test
		void allBundleCountsOnlyMonthsHavingEveryRequiredCourseAndSplitsWithinTheMonth() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ALL, null,
					DiscountValueType.FIXED, "200", List.of(MATHS, SCIENCE));

			DiscountOutcome outcome = apply(List.of(monthly(1, MATHS, 2026, 9, "1000.00"),
					monthly(2, SCIENCE, 2026, 9, "3000.00"), monthly(3, MATHS, 2026, 10, "1000.00")), List.of(bundle),
					null);

			AppliedDiscount applied = outcome.applied().get(0);
			assertThat(applied.amount()).isEqualByComparingTo("200.00");
			// September only (October has Maths alone); 200 split 1:3 within September.
			assertThat(applied.allocation()).containsOnlyKeys(1, 2);
			assertThat(applied.allocation().get(1)).isEqualByComparingTo("50.00");
			assertThat(applied.allocation().get(2)).isEqualByComparingTo("150.00");
		}

		@Test
		void eachMonthIsCappedAtThatMonthsEligibleTotal() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "500", List.of(MATHS));

			DiscountOutcome outcome = apply(List.of(monthly(1, MATHS, 2026, 9, "300.00"),
					monthly(2, MATHS, 2026, 10, "1000.00")), List.of(bundle), null);

			assertThat(outcome.applied().get(0).allocation().get(1)).isEqualByComparingTo("300.00");
			assertThat(outcome.applied().get(0).allocation().get(2)).isEqualByComparingTo("500.00");
			assertThat(outcome.total()).isEqualByComparingTo("800.00");
		}

		@Test
		void linesWithoutAMonthCountOnceAndAnOrderOnlyQualifyingAcrossMonthsAppliesOnce() {
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ALL, null,
					DiscountValueType.FIXED, "200", List.of(MATHS, SCIENCE));

			// Lessons have no month: one group -> once.
			assertThat(apply(List.of(lesson(1, MATHS, "500.00"), lesson(2, SCIENCE, "500.00"),
					lesson(3, MATHS, "500.00")), List.of(bundle), null).total()).isEqualByComparingTo("200.00");
			// Maths in Sep, Science in Oct: no single month has both, but the order does -> once (D4).
			assertThat(apply(List.of(monthly(1, MATHS, 2026, 9, "1000.00"), monthly(2, SCIENCE, 2026, 10, "1000.00")),
					List.of(bundle), null).total()).isEqualByComparingTo("200.00");
		}

		@Test
		void percentageAndAdvanceAreUnchanged() {
			RuleSnapshot percent = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.PERCENTAGE, "10", List.of(MATHS));
			List<CartLine> twoMonths = List.of(monthly(1, MATHS, 2026, 11, "1000.00"),
					monthly(2, MATHS, 2026, 12, "1000.00"));
			assertThat(apply(twoMonths, List.of(percent), null).total()).isEqualByComparingTo("200.00");

			RuleSnapshot advance = rule(DiscountType.ADVANCE_PAYMENT, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "200", List.of(MATHS));
			assertThat(apply(twoMonths, List.of(advance), null).total()).isEqualByComparingTo("200.00");
		}

	}

	@Nested
	class Combination {

		@Test
		void greatestSavingWinsAndLinesAreNeverStacked() {
			RuleSnapshot small = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "100", List.of(MATHS));
			RuleSnapshot big = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "300", List.of(MATHS));

			DiscountOutcome outcome = apply(List.of(lesson(1, MATHS, "1000.00")), List.of(small, big), null);

			assertThat(outcome.applied()).hasSize(1);
			assertThat(outcome.applied().get(0).rule()).isEqualTo(big);
		}

		@Test
		void remainingRulesApplyToUntakenLines() {
			RuleSnapshot maths = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "300", List.of(MATHS));
			RuleSnapshot both = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ALL, null,
					DiscountValueType.PERCENTAGE, "10", List.of(MATHS, SCIENCE));

			DiscountOutcome outcome = apply(List.of(lesson(1, MATHS, "1000.00"), lesson(2, SCIENCE, "1000.00")),
					List.of(maths, both), null);

			// maths 300 > both 200 -> maths takes line 1; both still qualifies on the full base and gets line 2.
			assertThat(outcome.applied()).hasSize(2);
			assertThat(outcome.applied().get(0).rule()).isEqualTo(maths);
			assertThat(outcome.applied().get(1).lineNos()).containsExactly(2);
			assertThat(outcome.applied().get(1).amount()).isEqualByComparingTo("100.00");
			assertThat(outcome.total()).isEqualByComparingTo("400.00");
		}

		@Test
		void tiesPreferBundleOverAdvanceOverCode() {
			RuleSnapshot code = rule(DiscountType.DISCOUNT_CODE, SelectionMode.ANY, null, DiscountValueType.FIXED,
					"100", List.of(MATHS));
			RuleSnapshot advance = rule(DiscountType.ADVANCE_PAYMENT, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "100", List.of(MATHS));
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "100", List.of(MATHS));

			DiscountOutcome outcome = apply(List.of(monthly(1, MATHS, 2026, 11, "1000.00")),
					List.of(code, advance, bundle), code.ruleId());

			assertThat(outcome.applied()).hasSize(1);
			assertThat(outcome.applied().get(0).rule()).isEqualTo(bundle);
			assertThat(outcome.codeStatus()).isEqualTo(CodeStatus.NOT_APPLIED_BETTER_DISCOUNT);

			DiscountOutcome withoutBundle = apply(List.of(monthly(1, MATHS, 2026, 11, "1000.00")),
					List.of(code, advance), code.ruleId());
			assertThat(withoutBundle.applied().get(0).rule()).isEqualTo(advance);
		}

		@Test
		void equalTypeTiesPreferEarlierValidFromThenRuleId() {
			UUID lowId = UUID.fromString("00000000-0000-0000-0000-000000000001");
			UUID highId = UUID.fromString("00000000-0000-0000-0000-000000000002");
			RuleSnapshot laterStart = rule(lowId, DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "100", List.of(MATHS), LocalDate.of(2026, 5, 1), null);
			RuleSnapshot earlierStart = rule(highId, DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "100", List.of(MATHS), LocalDate.of(2026, 2, 1), null);

			assertThat(apply(List.of(lesson(1, MATHS, "500.00")), List.of(laterStart, earlierStart), null).applied()
				.get(0)
				.rule()).isEqualTo(earlierStart);

			RuleSnapshot sameStartHigh = rule(highId, DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "100", List.of(MATHS), LocalDate.of(2026, 2, 1), null);
			RuleSnapshot sameStartLow = rule(lowId, DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "100", List.of(MATHS), LocalDate.of(2026, 2, 1), null);
			assertThat(apply(List.of(lesson(1, MATHS, "500.00")), List.of(sameStartHigh, sameStartLow), null)
				.applied()
				.get(0)
				.rule()).isEqualTo(sameStartLow);
		}

		@Test
		void codeAppliesWhenItIsTheBestAndIsNotEligibleWhenConditionsFail() {
			RuleSnapshot code = rule(DiscountType.DISCOUNT_CODE, SelectionMode.ANY, null,
					DiscountValueType.PERCENTAGE, "20", List.of(MATHS));
			RuleSnapshot bundle = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null,
					DiscountValueType.FIXED, "50", List.of(MATHS));

			DiscountOutcome outcome = apply(List.of(lesson(1, MATHS, "1000.00")), List.of(code, bundle),
					code.ruleId());
			assertThat(outcome.codeStatus()).isEqualTo(CodeStatus.APPLIED);
			assertThat(outcome.total()).isEqualByComparingTo("200.00");

			DiscountOutcome ineligible = apply(List.of(lesson(1, ART, "1000.00")), List.of(code), code.ruleId());
			assertThat(ineligible.codeStatus()).isEqualTo(CodeStatus.NOT_ELIGIBLE);
			assertThat(ineligible.applied()).isEmpty();
		}

		@Test
		void resultIsDeterministicRegardlessOfRuleOrder() {
			RuleSnapshot a = rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY, null, DiscountValueType.FIXED,
					"100", List.of(MATHS));
			RuleSnapshot b = rule(DiscountType.ADVANCE_PAYMENT, SelectionMode.ANY, null,
					DiscountValueType.PERCENTAGE, "10", List.of(MATHS, SCIENCE));
			List<CartLine> lines = List.of(monthly(1, MATHS, 2026, 11, "1000.00"),
					monthly(2, SCIENCE, 2026, 12, "777.77"));

			DiscountOutcome first = apply(lines, List.of(a, b), null);
			DiscountOutcome second = apply(lines, List.of(b, a), null);

			assertThat(first.total()).isEqualByComparingTo(second.total());
			assertThat(first.applied().get(0).rule()).isEqualTo(second.applied().get(0).rule());
			assertThat(first.applied().get(0).allocation()).isEqualTo(second.applied().get(0).allocation());
		}

	}

	@Nested
	class Gating {

		private final RuleSnapshot code = rule(DiscountType.DISCOUNT_CODE, SelectionMode.ANY, null,
				DiscountValueType.FIXED, "100", List.of(MATHS));

		@Test
		void availableRulePassesTheGate() {
			assertThat(DiscountPricingService.gateFailure(code, true, LocalDate.of(2026, 10, 20), "LKR", true))
				.isEmpty();
		}

		@Test
		void datesAreInclusive() {
			assertThat(DiscountPricingService.gateFailure(code, true, LocalDate.of(2026, 1, 1), "LKR", true)).isEmpty();
			assertThat(DiscountPricingService.gateFailure(code, true, LocalDate.of(2026, 12, 31), "LKR", true))
				.isEmpty();
			assertThat(DiscountPricingService.gateFailure(code, true, LocalDate.of(2027, 1, 1), "LKR", true))
				.contains(CodeStatus.EXPIRED);
			assertThat(DiscountPricingService.gateFailure(code, true, LocalDate.of(2025, 12, 31), "LKR", true))
				.contains(CodeStatus.NOT_YET_VALID);
		}

		@Test
		void disabledExpiredAndNotYetValidAreShownToStudentsAsInvalid() {
			// Owner decision 2026-10-21 ("harden fully"): a real but unusable code
			// must look exactly like a made-up one.
			assertThat(CodeStatus.DISABLED.studentFacing()).isEqualTo(CodeStatus.INVALID);
			assertThat(CodeStatus.EXPIRED.studentFacing()).isEqualTo(CodeStatus.INVALID);
			assertThat(CodeStatus.NOT_YET_VALID.studentFacing()).isEqualTo(CodeStatus.INVALID);
			assertThat(CodeStatus.ALREADY_USED.studentFacing()).isEqualTo(CodeStatus.NOT_ELIGIBLE);
			for (CodeStatus kept : List.of(CodeStatus.APPLIED, CodeStatus.INVALID, CodeStatus.NOT_ELIGIBLE,
					CodeStatus.USAGE_LIMIT_REACHED, CodeStatus.NOT_APPLIED_BETTER_DISCOUNT)) {
				assertThat(kept.studentFacing()).isEqualTo(kept);
			}
			// Every status a student can receive.
			assertThat(java.util.Arrays.stream(CodeStatus.values()).map(CodeStatus::studentFacing).distinct())
				.containsExactlyInAnyOrder(CodeStatus.APPLIED, CodeStatus.INVALID, CodeStatus.NOT_ELIGIBLE,
						CodeStatus.USAGE_LIMIT_REACHED, CodeStatus.NOT_APPLIED_BETTER_DISCOUNT);
		}

		@Test
		void disabledCurrencyAndCapacity() {
			LocalDate today = LocalDate.of(2026, 10, 20);
			assertThat(DiscountPricingService.gateFailure(code, false, today, "LKR", true))
				.contains(CodeStatus.DISABLED);
			assertThat(DiscountPricingService.gateFailure(code, true, today, "USD", true))
				.contains(CodeStatus.NOT_ELIGIBLE);
			assertThat(DiscountPricingService.gateFailure(code, true, today, "LKR", false))
				.contains(CodeStatus.USAGE_LIMIT_REACHED);
		}

		@Test
		void codesAreTrimmedUpperCasedAndPatternChecked() {
			assertThat(DiscountPricingService.normalizeCode("  save-10_x ")).isEqualTo("SAVE-10_X");
			assertThat(DiscountPricingService.normalizeCode("   ")).isNull();
			assertThat(DiscountPricingService.isWellFormedCode("SAVE-10_X")).isTrue();
			assertThat(DiscountPricingService.isWellFormedCode("AB")).isFalse();
			// Minimum length 6 (owner decision 2026-10-21; V92 CHECK).
			assertThat(DiscountPricingService.isWellFormedCode("ABCDE")).isFalse();
			assertThat(DiscountPricingService.isWellFormedCode("ABCDEF")).isTrue();
			assertThat(DiscountPricingService.isWellFormedCode("A".repeat(40))).isTrue();
			assertThat(DiscountPricingService.isWellFormedCode("SAVE 10")).isFalse();
			assertThat(DiscountPricingService.isWellFormedCode("A".repeat(41))).isFalse();
		}

	}

}
