package com.lms.paymentmanagement.discount.service;

import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.ART;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.MATHS;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.NOW;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.SCIENCE;
import static com.lms.paymentmanagement.discount.service.DiscountPricingServiceTest.monthly;
import static org.assertj.core.api.Assertions.assertThat;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.DiscountValueType;
import com.lms.paymentmanagement.discount.domain.SelectionMode;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedDiscount;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import com.lms.paymentmanagement.discount.service.DiscountPricing.DiscountOutcome;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardCourse;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardMonthOffer;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardPlan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** D2 / D6 free-course rewards: window, conversion, acceptance, prerequisites, no cycles. */
class FreeCourseEvaluatorTest {

	private final DiscountPricingService service = new DiscountPricingService();

	private static RuleSnapshot freeRule(UUID id, List<UUID> required, List<UUID> rewards, int months) {
		return new RuleSnapshot(id, UUID.randomUUID(), DiscountType.BUNDLE_FREE_COURSE, "Free " + months, null,
				SelectionMode.ALL, null, null, null, months, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null,
				null, "LKR", required, rewards);
	}

	/** Art offers Sep 2026 .. Mar 2027 (all AVAILABLE) at 800.00. */
	private static RewardCourse art(String... unavailable) {
		List<RewardMonthOffer> months = new ArrayList<>();
		YearMonth month = YearMonth.of(2026, 9);
		while (!month.isAfter(YearMonth.of(2027, 3))) {
			if (!List.of(unavailable).contains(month.toString())) {
				months.add(new RewardMonthOffer(ART, month.getYear(), month.getMonthValue(),
						"MONTHLY:" + ART + ":" + month.getYear() + "-" + month.getMonthValue(), new BigDecimal("800.00"),
						month.toString()));
			}
			month = month.plusMonths(1);
		}
		return new RewardCourse(ART, "Art", true, months);
	}

	private RewardPlan plan(List<RuleSnapshot> rules, List<CartLine> lines, RewardCourse reward, Set<UUID> accepted) {
		return service.planRewards(rules, lines, Map.of(reward.courseId(), reward), accepted, NOW);
	}

	@Test
	void offersOnlyTheConfiguredWindowStartingThisMonth() {
		RuleSnapshot rule = freeRule(UUID.randomUUID(), List.of(MATHS, SCIENCE), List.of(ART), 3);
		List<CartLine> lines = List.of(monthly(1, MATHS, 2026, 10, "1000.00"), monthly(2, SCIENCE, 2026, 10, "900.00"));

		RewardPlan plan = plan(List.of(rule), lines, art(), Set.of());

		assertThat(plan.applied()).isEmpty();
		assertThat(plan.offers()).hasSize(1);
		assertThat(plan.offers().get(0).months()).extracting(RewardMonthOffer::period)
			.containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12));
	}

	@Test
	void unavailableMonthsAreSkippedNotShifted() {
		RuleSnapshot rule = freeRule(UUID.randomUUID(), List.of(MATHS), List.of(ART), 2);

		RewardPlan plan = plan(List.of(rule), List.of(monthly(1, MATHS, 2026, 10, "1000.00")), art("2026-10"),
				Set.of(rule.ruleId()));

		assertThat(plan.applied()).hasSize(1);
		assertThat(plan.applied().get(0).addedMonths()).extracting(RewardMonthOffer::period)
			.containsExactly(YearMonth.of(2026, 11));
	}

	@Test
	void acceptedRewardAddsMonthsAndSelectedMonthsConvertInPlace() {
		RuleSnapshot rule = freeRule(UUID.randomUUID(), List.of(MATHS), List.of(ART), 2);
		CartLine selectedArt = monthly(2, ART, 2026, 10, "800.00");
		List<CartLine> lines = List.of(monthly(1, MATHS, 2026, 10, "1000.00"), selectedArt);

		RewardPlan notAccepted = plan(List.of(rule), lines, art(), Set.of());
		assertThat(notAccepted.applied()).hasSize(1);
		assertThat(notAccepted.applied().get(0).convertedKeys()).containsExactly(selectedArt.key());
		assertThat(notAccepted.applied().get(0).addedMonths()).isEmpty();
		assertThat(notAccepted.offers().get(0).months()).extracting(RewardMonthOffer::period)
			.containsExactly(YearMonth.of(2026, 11));

		RewardPlan accepted = plan(List.of(rule), lines, art(), Set.of(rule.ruleId()));
		assertThat(accepted.applied().get(0).addedMonths()).extracting(RewardMonthOffer::period)
			.containsExactly(YearMonth.of(2026, 11));
		assertThat(accepted.offers()).isEmpty();
	}

	@Test
	void rewardLinesAreFullyDiscountedAndNeverQualifyOtherRules() {
		RuleSnapshot free = freeRule(UUID.randomUUID(), List.of(MATHS), List.of(ART), 1);
		CartLine maths = monthly(1, MATHS, 2026, 10, "1000.00");
		CartLine artLine = monthly(2, ART, 2026, 10, "800.00");
		RewardPlan plan = plan(List.of(free), List.of(maths, artLine), art(), Set.of());
		Map<String, UUID> rewardKeys = plan.rewardKeys();
		List<CartLine> finalLines = List.of(maths, new CartLine(2, artLine.key(), ART, "MONTHLY", 2026, 10,
				artLine.unitAmount(), rewardKeys.get(artLine.key())));
		RuleSnapshot artBundle = DiscountPricingServiceTest.rule(DiscountType.BUNDLE_AMOUNT_OFF, SelectionMode.ANY,
				null, DiscountValueType.FIXED, "100", List.of(ART));

		DiscountOutcome outcome = service.applyDiscounts(finalLines, plan, List.of(artBundle), null, NOW);

		assertThat(outcome.applied()).hasSize(1);
		AppliedDiscount reward = outcome.applied().get(0);
		assertThat(reward.rule()).isEqualTo(free);
		assertThat(reward.allocation()).containsExactly(Map.entry(2, new BigDecimal("800.00")));
		assertThat(reward.qualification().rewardMonths()).containsExactly(ART + ":2026-10");
	}

	@Test
	void removingAPrerequisiteRemovesTheRewardWithANotice() {
		RuleSnapshot rule = freeRule(UUID.randomUUID(), List.of(MATHS, SCIENCE), List.of(ART), 2);

		RewardPlan plan = plan(List.of(rule), List.of(monthly(1, MATHS, 2026, 10, "1000.00")), art(),
				Set.of(rule.ruleId()));

		assertThat(plan.applied()).isEmpty();
		assertThat(plan.offers()).isEmpty();
		assertThat(plan.notices()).singleElement().asString().contains(rule.name());
	}

	@Test
	void unknownAcceptedRuleGetsAGenericNoticeOnly() {
		RewardPlan plan = plan(List.of(), List.of(monthly(1, MATHS, 2026, 10, "1000.00")), art(),
				Set.of(UUID.randomUUID()));

		assertThat(plan.notices()).containsExactly("A free course offer you accepted is no longer available.");
	}

	@Test
	void mutualRewardsNeverCycleAndNeverDuplicate() {
		UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
		// first: buy Maths, get Art free; second: buy Art, get Maths free.
		RuleSnapshot mathsGivesArt = freeRule(first, List.of(MATHS), List.of(ART), 1);
		RuleSnapshot artGivesMaths = freeRule(second, List.of(ART), List.of(MATHS), 1);
		RewardCourse mathsReward = new RewardCourse(MATHS, "Maths", true,
				List.of(new RewardMonthOffer(MATHS, 2026, 10, "MONTHLY:" + MATHS + ":2026-10", new BigDecimal("800.00"),
						"October 2026")));
		List<CartLine> lines = List.of(monthly(1, MATHS, 2026, 10, "800.00"), monthly(2, ART, 2026, 10, "800.00"));

		RewardPlan plan = service.planRewards(List.of(mathsGivesArt, artGivesMaths), lines,
				Map.of(ART, art(), MATHS, mathsReward), Set.of(), NOW);

		assertThat(plan.applied()).hasSize(1);
		assertThat(plan.applied().get(0).rule()).isEqualTo(mathsGivesArt);
		assertThat(plan.rewardKeys()).hasSize(1);
	}

	@Test
	void ineligibleRewardCourseIsNeverOfferedButAsksToEnroll() {
		RuleSnapshot rule = freeRule(UUID.randomUUID(), List.of(MATHS), List.of(ART), 1);
		RewardCourse notEnrolled = new RewardCourse(ART, "Art", false, true, List.of());

		RewardPlan plan = plan(List.of(rule), List.of(monthly(1, MATHS, 2026, 10, "1000.00")), notEnrolled,
				Set.of());

		assertThat(plan.applied()).isEmpty();
		assertThat(plan.offers()).isEmpty();
		assertThat(plan.notices()).singleElement().asString().contains("Enroll in \"Art\"");
	}

}
