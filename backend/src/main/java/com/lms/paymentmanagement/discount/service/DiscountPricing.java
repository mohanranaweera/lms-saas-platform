package com.lms.paymentmanagement.discount.service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Input / output shapes of {@link DiscountPricingService} (DISC-01, spec §2).
 * All plain immutable records so the engine stays a pure, unit-testable
 * function of its inputs.
 */
public final class DiscountPricing {

	private DiscountPricing() {
	}

	/**
	 * Status of the code the student entered. {@link #studentFacing()} is what
	 * {@code codeResult.status} carries (owner decision 2026-10-21, "harden
	 * fully"): {@code APPLIED | INVALID | NOT_ELIGIBLE | USAGE_LIMIT_REACHED |
	 * NOT_APPLIED_BETTER_DISCOUNT}. {@link #EXPIRED}, {@link #DISABLED} and
	 * {@link #NOT_YET_VALID} are internal reasons only (kept for logging) and
	 * are reported as {@link #INVALID}, so a student cannot tell a real but
	 * unusable code from a made-up one; {@link #ALREADY_USED} (one use per
	 * student per rule) is reported as {@link #NOT_ELIGIBLE}.
	 */
	public enum CodeStatus {

		APPLIED, INVALID, EXPIRED, DISABLED, NOT_ELIGIBLE, USAGE_LIMIT_REACHED, NOT_APPLIED_BETTER_DISCOUNT,

		/** Internal: today is before the rule's {@code validFrom}. */
		NOT_YET_VALID,

		/** Internal: the student already holds a counting redemption of this rule. */
		ALREADY_USED;

		public CodeStatus studentFacing() {
			return switch (this) {
				case EXPIRED, DISABLED, NOT_YET_VALID -> INVALID;
				case ALREADY_USED -> NOT_ELIGIBLE;
				default -> this;
			};
		}

	}

	/**
	 * One priced line as the engine sees it.
	 * @param key the coverage key (unique within an order).
	 * @param scope {@code MONTHLY|MODULE|LESSON}.
	 * @param year/month explicit billing period of a MONTHLY line, else {@code null}.
	 * @param rewardRuleId the free-course rule this line is a reward of, else {@code null}.
	 */
	public record CartLine(int lineNo, String key, UUID courseId, String scope, Integer year, Integer month,
			BigDecimal unitAmount, UUID rewardRuleId) {

		public CartLine(int lineNo, String key, UUID courseId, String scope, Integer year, Integer month,
				BigDecimal unitAmount) {
			this(lineNo, key, courseId, scope, year, month, unitAmount, null);
		}

		public boolean reward() {
			return rewardRuleId != null;
		}

		public YearMonth period() {
			return year == null || month == null ? null : YearMonth.of(year, month);
		}

	}

	/** One AVAILABLE MONTHLY offer of a reward course (an existing composer offer). */
	public record RewardMonthOffer(UUID courseId, int year, int month, String key, BigDecimal unitAmount, String label) {

		public YearMonth period() {
			return YearMonth.of(year, month);
		}

	}

	/**
	 * A reward course as offered to this caller.
	 * @param eligible the caller may buy it here (non-DRAFT course the caller
	 * participates in, MONTHLY-priced, in the cart currency).
	 * @param enrollmentRequired not eligible only because the caller is not
	 * enrolled in this (public) course - the student is told to enroll first.
	 * @param availableMonths its AVAILABLE (not paid, not pending) MONTHLY offers.
	 */
	public record RewardCourse(UUID courseId, String title, boolean eligible, boolean enrollmentRequired,
			List<RewardMonthOffer> availableMonths) {

		public RewardCourse {
			availableMonths = availableMonths == null ? List.of() : List.copyOf(availableMonths);
		}

		public RewardCourse(UUID courseId, String title, boolean eligible, List<RewardMonthOffer> availableMonths) {
			this(courseId, title, eligible, false, availableMonths);
		}

	}

	/**
	 * A free-course rule that applies: selected lines converted in place
	 * ({@code convertedKeys}) and accepted months added ({@code addedMonths}).
	 */
	public record AppliedReward(RuleSnapshot rule, Set<String> convertedKeys, List<RewardMonthOffer> addedMonths,
			List<UUID> qualifyingCourseIds) {

	}

	/** Months of a reward course the student may still accept for {@code rule}. */
	public record RewardOfferPlan(RuleSnapshot rule, UUID courseId, String courseTitle,
			List<RewardMonthOffer> months) {

	}

	/** Phase 1 (before fees): which reward lines exist, what is offered, and notices. */
	public record RewardPlan(List<AppliedReward> applied, List<RewardOfferPlan> offers, List<String> notices) {

		public static final RewardPlan EMPTY = new RewardPlan(List.of(), List.of(), List.of());

		/** coverage key -> free-course rule id, for every converted or added reward line. */
		public Map<String, UUID> rewardKeys() {
			Map<String, UUID> keys = new java.util.LinkedHashMap<>();
			for (AppliedReward reward : applied) {
				reward.convertedKeys().forEach(key -> keys.put(key, reward.rule().ruleId()));
				reward.addedMonths().forEach(month -> keys.put(month.key(), reward.rule().ruleId()));
			}
			return keys;
		}

	}

	/**
	 * What qualified a discount (snapshotted as JSON on the order).
	 * @param advancePeriods ADVANCE_PAYMENT: {@code yyyy-MM} of the discounted advance lines.
	 * @param rewardMonths BUNDLE_FREE_COURSE: {@code courseId:yyyy-MM} of the reward lines.
	 */
	public record Qualification(List<UUID> qualifyingCourseIds, List<String> advancePeriods, List<String> rewardMonths,
			List<UUID> rewardCourseIds) {

	}

	/** One applied discount with its exact per-line allocation (sums to {@code amount}). */
	public record AppliedDiscount(RuleSnapshot rule, BigDecimal amount, Map<Integer, BigDecimal> allocation,
			Qualification qualification) {

		public List<Integer> lineNos() {
			return allocation.keySet().stream().sorted().toList();
		}

	}

	/** Phase 3 result. {@code codeStatus} is {@code null} when no code rule was evaluated. */
	public record DiscountOutcome(List<AppliedDiscount> applied, CodeStatus codeStatus) {

		public BigDecimal total() {
			return applied.stream().map(AppliedDiscount::amount).reduce(DiscountPricingService.ZERO, BigDecimal::add);
		}

	}

}
