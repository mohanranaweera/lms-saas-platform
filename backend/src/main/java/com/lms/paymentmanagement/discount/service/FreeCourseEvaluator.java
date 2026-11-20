package com.lms.paymentmanagement.discount.service;

import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedReward;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardCourse;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardMonthOffer;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardOfferPlan;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardPlan;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Course Bundle - Free Course (D2, D6), phase 1 of pricing - runs BEFORE fees
 * so added reward lines get their facility fee from the existing fee rules.
 *
 * <ul>
 * <li>Qualification: the rule's selection mode over the non-reward lines of
 * its required courses + minimum purchase; lines that became reward lines of
 * any applied free-course rule never count as qualifiers (no self / cyclic
 * rewards).</li>
 * <li>Reward months: the calendar window [current tenant month, current
 * month + N - 1] intersected with the reward course's AVAILABLE MONTHLY
 * offers. A month already selected is converted in place; a missing month is
 * added only when the rule id is in {@code acceptedRuleIds}, else it is
 * offered.</li>
 * <li>Rules are processed by greatest saving (then earlier {@code validFrom},
 * then rule id); a month is rewarded at most once. If an applied rule loses a
 * qualifier to another rule's reward line it is dropped and the plan is
 * recomputed without it (terminates: the candidate set only shrinks).</li>
 * </ul>
 */
public final class FreeCourseEvaluator {

	public RewardPlan plan(List<RuleSnapshot> rules, List<CartLine> baseLines, Map<UUID, RewardCourse> rewardCourses,
			Set<UUID> acceptedRuleIds, YearMonth currentMonth) {
		if (rules.isEmpty()) {
			return acceptedRuleIds.isEmpty() ? RewardPlan.EMPTY
					: new RewardPlan(List.of(), List.of(), notices(List.of(), List.of(), acceptedRuleIds));
		}
		Set<String> selectedKeys = new LinkedHashSet<>();
		baseLines.forEach(line -> selectedKeys.add(line.key()));
		List<RuleSnapshot> candidates = new ArrayList<>(rules);
		List<AppliedReward> applied;
		Set<String> taken;
		List<RuleSnapshot> ordered;
		while (true) {
			ordered = new ArrayList<>(candidates);
			ordered.sort(Comparator
				.comparing((RuleSnapshot rule) -> potential(rule, selectedKeys, rewardCourses, acceptedRuleIds,
						currentMonth))
				.reversed()
				.thenComparing(RuleSnapshot::validFrom)
				.thenComparing(rule -> rule.ruleId().toString()));
			applied = new ArrayList<>();
			taken = new LinkedHashSet<>();
			for (RuleSnapshot rule : ordered) {
				List<CartLine> qualifiers = untaken(baseLines, taken);
				if (!DiscountPricingService.qualifies(rule, qualifiers, qualifiers)) {
					continue;
				}
				Set<String> converted = new LinkedHashSet<>();
				List<RewardMonthOffer> added = new ArrayList<>();
				for (RewardMonthOffer month : windowMonths(rule, rewardCourses, currentMonth)) {
					if (taken.contains(month.key()) || converted.contains(month.key())) {
						continue;
					}
					if (selectedKeys.contains(month.key())) {
						converted.add(month.key());
					}
					else if (acceptedRuleIds.contains(rule.ruleId())) {
						added.add(month);
					}
				}
				if (converted.isEmpty() && added.isEmpty()) {
					continue;
				}
				applied.add(new AppliedReward(rule, Set.copyOf(converted), List.copyOf(added),
						DiscountPricingService.qualifyingCourses(rule, qualifiers)));
				taken.addAll(converted);
				for (RewardMonthOffer month : added) {
					taken.add(month.key());
				}
			}
			RuleSnapshot invalid = null;
			for (AppliedReward reward : applied) {
				List<CartLine> qualifiers = untaken(baseLines, taken);
				if (!DiscountPricingService.qualifies(reward.rule(), qualifiers, qualifiers)) {
					invalid = reward.rule();
					break;
				}
			}
			if (invalid == null) {
				break;
			}
			candidates.remove(invalid);
		}

		List<RewardOfferPlan> offers = new ArrayList<>();
		List<String> enrollNotices = new ArrayList<>();
		List<CartLine> finalQualifiers = untaken(baseLines, taken);
		for (RuleSnapshot rule : ordered) {
			if (!DiscountPricingService.qualifies(rule, finalQualifiers, finalQualifiers)) {
				continue;
			}
			for (UUID courseId : rule.rewardCourseIds()) {
				RewardCourse course = rewardCourses.get(courseId);
				if (course != null && !course.eligible() && course.enrollmentRequired() && course.title() != null) {
					String notice = "Enroll in \"" + course.title() + "\" to receive its free months from \""
							+ rule.name() + "\".";
					if (!enrollNotices.contains(notice)) {
						enrollNotices.add(notice);
					}
				}
				if (course == null || !course.eligible() || acceptedRuleIds.contains(rule.ruleId())) {
					continue;
				}
				List<RewardMonthOffer> missing = new ArrayList<>();
				for (RewardMonthOffer month : window(rule, course, currentMonth)) {
					if (!taken.contains(month.key()) && !selectedKeys.contains(month.key())) {
						missing.add(month);
					}
				}
				if (!missing.isEmpty()) {
					offers.add(new RewardOfferPlan(rule, courseId, course.title(), List.copyOf(missing)));
				}
			}
		}
		List<String> notices = new ArrayList<>(notices(rules, applied, acceptedRuleIds));
		notices.addAll(enrollNotices);
		return new RewardPlan(List.copyOf(applied), List.copyOf(offers), List.copyOf(notices));
	}

	/** Every reward month of every eligible reward course of {@code rule}, in course then period order. */
	static List<RewardMonthOffer> windowMonths(RuleSnapshot rule, Map<UUID, RewardCourse> rewardCourses,
			YearMonth currentMonth) {
		List<RewardMonthOffer> months = new ArrayList<>();
		for (UUID courseId : rule.rewardCourseIds()) {
			RewardCourse course = rewardCourses.get(courseId);
			if (course != null && course.eligible()) {
				months.addAll(window(rule, course, currentMonth));
			}
		}
		return months;
	}

	/** [current month, current month + N - 1] ∩ the course's AVAILABLE offered months, in period order. */
	static List<RewardMonthOffer> window(RuleSnapshot rule, RewardCourse course, YearMonth currentMonth) {
		int freeMonths = rule.freeMonths() == null ? 0 : rule.freeMonths();
		YearMonth last = currentMonth.plusMonths(Math.max(freeMonths, 1) - 1L);
		if (freeMonths < 1) {
			return List.of();
		}
		return course.availableMonths()
			.stream()
			.filter(month -> !month.period().isBefore(currentMonth) && !month.period().isAfter(last))
			.sorted(Comparator.comparing(RewardMonthOffer::period))
			.toList();
	}

	private static BigDecimal potential(RuleSnapshot rule, Set<String> selectedKeys,
			Map<UUID, RewardCourse> rewardCourses, Set<UUID> acceptedRuleIds, YearMonth currentMonth) {
		boolean accepted = acceptedRuleIds.contains(rule.ruleId());
		BigDecimal total = DiscountPricingService.ZERO;
		for (RewardMonthOffer month : windowMonths(rule, rewardCourses, currentMonth)) {
			if (accepted || selectedKeys.contains(month.key())) {
				total = total.add(month.unitAmount());
			}
		}
		return total;
	}

	private static List<CartLine> untaken(List<CartLine> lines, Set<String> takenKeys) {
		return lines.stream().filter(line -> !takenKeys.contains(line.key())).toList();
	}

	/**
	 * A notice for every accepted reward that is not applied: named when the
	 * rule is a current candidate (its name is student-visible), generic
	 * otherwise (no information about other rules is disclosed).
	 */
	private static List<String> notices(List<RuleSnapshot> rules, List<AppliedReward> applied,
			Set<UUID> acceptedRuleIds) {
		List<String> notices = new ArrayList<>();
		Set<UUID> appliedIds = new LinkedHashSet<>();
		applied.forEach(reward -> appliedIds.add(reward.rule().ruleId()));
		boolean generic = false;
		for (UUID accepted : acceptedRuleIds) {
			if (appliedIds.contains(accepted)) {
				continue;
			}
			RuleSnapshot rule = rules.stream().filter(r -> r.ruleId().equals(accepted)).findFirst().orElse(null);
			if (rule != null) {
				notices.add("The free course offer \"" + rule.name()
						+ "\" was removed because your cart no longer meets its conditions.");
			}
			else {
				generic = true;
			}
		}
		if (generic) {
			notices.add("A free course offer you accepted is no longer available.");
		}
		return List.copyOf(notices);
	}

}
