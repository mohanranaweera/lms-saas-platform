package com.lms.paymentmanagement.discount.service;

import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.DiscountValueType;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedDiscount;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedReward;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CartLine;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CodeStatus;
import com.lms.paymentmanagement.discount.service.DiscountPricing.DiscountOutcome;
import com.lms.paymentmanagement.discount.service.DiscountPricing.Qualification;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardCourse;
import com.lms.paymentmanagement.discount.service.DiscountPricing.RewardPlan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The DISC-01 discount engine (spec §2) - pure: no repositories, no clock, no
 * security context; everything is a parameter, so it is fully unit-testable.
 *
 * <ol>
 * <li>{@link #planRewards} (phase 1, before fees): free-course rewards (D2) -
 * see {@link FreeCourseEvaluator}.</li>
 * <li>{@link #applyDiscounts} (phase 3, on the final numbered lines): the
 * applied free-course rewards (100% of each reward line) first, then the
 * greedy combination of the value rules (D6): evaluate every candidate on the
 * untaken lines, apply the greatest saving (ties: free-course > bundle >
 * advance > code, then earlier {@code validFrom}, then rule id), remove its
 * lines, repeat. Qualification is always evaluated on the full non-reward
 * base; the discount lands only on untaken eligible lines; one discount per
 * line (no stacking).</li>
 * </ol>
 * Amounts: FIXED = min(value, eligible total), applied once per order;
 * PERCENTAGE = round_half_up(eligible total x p / 100, 2); then {@link
 * DiscountAllocator} (D4). Facility fees and postage are never part of the
 * engine's input, so they are never discounted.
 */
@Component
public class DiscountPricingService {

	public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z0-9_-]{6,40}$");

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final Map<DiscountType, DiscountEvaluator> evaluators = new EnumMap<>(DiscountType.class);

	private final FreeCourseEvaluator freeCourseEvaluator = new FreeCourseEvaluator();

	public DiscountPricingService() {
		for (DiscountEvaluator evaluator : List.of(new AdvancePaymentEvaluator(), new BundleAmountOffEvaluator(),
				new DiscountCodeEvaluator())) {
			evaluators.put(evaluator.type(), evaluator);
		}
	}

	// ------------------------------------------------------------------
	// Codes and gating.
	// ------------------------------------------------------------------

	/** D7: {@code strip()} + upper-case (ROOT); {@code null} for null/blank. */
	public static String normalizeCode(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		return raw.strip().toUpperCase(Locale.ROOT);
	}

	/** D7 (hardened 2026-10-21): 6-40 of {@code A-Z 0-9 _ -} after normalization (V92 CHECK). */
	public static boolean isWellFormedCode(String normalized) {
		return normalized != null && CODE_PATTERN.matcher(normalized).matches();
	}

	/**
	 * Rule-level availability (spec §2 step 3): active, inside the inclusive
	 * tenant-date window (D10), same currency as the cart (D13), capacity left
	 * (D12). Empty = available; otherwise the INTERNAL reason (see {@link
	 * CodeStatus#studentFacing()} for what a student is shown).
	 */
	public static Optional<CodeStatus> gateFailure(RuleSnapshot rule, boolean active, LocalDate today,
			String cartCurrency, boolean capacityAvailable) {
		if (!active) {
			return Optional.of(CodeStatus.DISABLED);
		}
		if (today.isAfter(rule.validTo())) {
			return Optional.of(CodeStatus.EXPIRED);
		}
		if (today.isBefore(rule.validFrom())) {
			return Optional.of(CodeStatus.NOT_YET_VALID);
		}
		if (cartCurrency == null || !cartCurrency.equals(rule.currency())) {
			return Optional.of(CodeStatus.NOT_ELIGIBLE);
		}
		if (!capacityAvailable) {
			return Optional.of(CodeStatus.USAGE_LIMIT_REACHED);
		}
		return Optional.empty();
	}

	// ------------------------------------------------------------------
	// Phase 1: free-course rewards.
	// ------------------------------------------------------------------

	/**
	 * @param freeRules available BUNDLE_FREE_COURSE rules (already gated).
	 * @param baseLines the student's selected lines (no reward lines yet).
	 */
	public RewardPlan planRewards(List<RuleSnapshot> freeRules, List<CartLine> baseLines,
			Map<UUID, RewardCourse> rewardCourses, Set<UUID> acceptedRuleIds, YearMonth currentMonth) {
		return freeCourseEvaluator.plan(freeRules, baseLines, rewardCourses, acceptedRuleIds, currentMonth);
	}

	// ------------------------------------------------------------------
	// Phase 3: discounts on the final lines.
	// ------------------------------------------------------------------

	/**
	 * @param lines every final line (reward lines carry {@code rewardRuleId}).
	 * @param valueRules available ADVANCE_PAYMENT / BUNDLE_AMOUNT_OFF rules and
	 * the matching DISCOUNT_CODE rule (all already gated).
	 * @param codeRuleId the id of the entered code's rule when it is among
	 * {@code valueRules}, else {@code null}.
	 */
	public DiscountOutcome applyDiscounts(List<CartLine> lines, RewardPlan plan, List<RuleSnapshot> valueRules,
			UUID codeRuleId, YearMonth currentMonth) {
		List<AppliedDiscount> applied = new ArrayList<>();
		Set<Integer> taken = new HashSet<>();
		for (AppliedReward reward : plan.applied()) {
			List<CartLine> rewardLines = lines.stream()
				.filter(line -> reward.rule().ruleId().equals(line.rewardRuleId()))
				.toList();
			if (rewardLines.isEmpty()) {
				continue;
			}
			Map<Integer, BigDecimal> allocation = new LinkedHashMap<>();
			BigDecimal amount = ZERO;
			List<String> months = new ArrayList<>();
			Set<UUID> rewardCourses = new LinkedHashSet<>();
			for (CartLine line : rewardLines) {
				allocation.put(line.lineNo(), money(line.unitAmount()));
				amount = amount.add(money(line.unitAmount()));
				months.add(line.courseId() + ":" + line.period());
				rewardCourses.add(line.courseId());
				taken.add(line.lineNo());
			}
			applied.add(new AppliedDiscount(reward.rule(), amount, allocation,
					new Qualification(reward.qualifyingCourseIds(), List.of(), months, List.copyOf(rewardCourses))));
		}

		List<CartLine> base = lines.stream().filter(line -> !line.reward()).toList();
		List<RuleSnapshot> remaining = new ArrayList<>(valueRules);
		remaining.sort(Comparator.comparing(rule -> rule.ruleId().toString()));
		boolean codeApplied = false;
		while (!remaining.isEmpty()) {
			Candidate best = null;
			for (RuleSnapshot rule : remaining) {
				Candidate candidate = evaluate(rule, base, taken, currentMonth);
				if (candidate == null || candidate.amount().signum() <= 0) {
					continue;
				}
				if (best == null || BETTER.compare(candidate, best) < 0) {
					best = candidate;
				}
			}
			if (best == null) {
				break;
			}
			Map<Integer, BigDecimal> allocation = best.allocation();
			taken.addAll(allocation.keySet());
			// Lines in the candidate that received 0 (tiny percentage) stay untaken.
			applied.add(new AppliedDiscount(best.rule(), best.amount(), allocation, best.qualification()));
			remaining.remove(best.rule());
			if (best.rule().ruleId().equals(codeRuleId)) {
				codeApplied = true;
			}
		}

		CodeStatus codeStatus = null;
		if (codeRuleId != null) {
			if (codeApplied) {
				codeStatus = CodeStatus.APPLIED;
			}
			else {
				RuleSnapshot codeRule = valueRules.stream()
					.filter(rule -> rule.ruleId().equals(codeRuleId))
					.findFirst()
					.orElse(null);
				codeStatus = codeRule != null && evaluate(codeRule, base, Set.of(), currentMonth) != null
						? CodeStatus.NOT_APPLIED_BETTER_DISCOUNT : CodeStatus.NOT_ELIGIBLE;
			}
		}
		return new DiscountOutcome(List.copyOf(applied), codeStatus);
	}

	/**
	 * A qualified value rule with its untaken eligible lines, the amount it would
	 * save and that amount's exact per-line allocation.
	 */
	record Candidate(RuleSnapshot rule, List<CartLine> lines, BigDecimal amount, Map<Integer, BigDecimal> allocation,
			Qualification qualification) {

	}

	/** D6 ordering: greatest saving, then type priority, then earlier validFrom, then rule id. */
	private static final Comparator<Candidate> BETTER = Comparator
		.comparing(Candidate::amount, Comparator.reverseOrder())
		.thenComparingInt(candidate -> candidate.rule().type().tiePriority())
		.thenComparing(candidate -> candidate.rule().validFrom())
		.thenComparing(candidate -> candidate.rule().ruleId().toString());

	/**
	 * {@code null} when the rule does not qualify on the full non-reward base;
	 * otherwise its untaken eligible lines and amount (0 when all its eligible
	 * lines are already taken).
	 */
	Candidate evaluate(RuleSnapshot rule, List<CartLine> base, Set<Integer> taken, YearMonth currentMonth) {
		DiscountEvaluator evaluator = evaluators.get(rule.type());
		if (evaluator == null) {
			return null;
		}
		List<CartLine> eligible = base.stream()
			.filter(line -> evaluator.isEligibleLine(rule, line, currentMonth))
			.toList();
		if (!qualifies(rule, eligible, base)) {
			return null;
		}
		List<CartLine> untaken = eligible.stream().filter(line -> !taken.contains(line.lineNo())).toList();
		Map<Integer, BigDecimal> allocation = appliesPerBillingMonth(rule)
				? perBillingMonthAllocation(rule, eligible, untaken) : null;
		if (allocation == null) {
			BigDecimal sum = sum(untaken);
			allocation = DiscountAllocator.allocate(amountFor(rule, sum), untaken);
		}
		BigDecimal amount = allocation.values().stream().reduce(ZERO, BigDecimal::add);
		List<String> advancePeriods = rule.type() == DiscountType.ADVANCE_PAYMENT
				? untaken.stream().map(line -> line.period().toString()).distinct().sorted().toList() : List.of();
		return new Candidate(rule, untaken, amount, allocation,
				new Qualification(qualifyingCourses(rule, eligible), advancePeriods, List.of(), List.of()));
	}

	/**
	 * D4a (owner decision 2026-10-21): a FIXED Course Bundle - Amount Off or
	 * Discount Code amount counts once per qualifying billing month, not once
	 * per order. Percentages and Advance Payment keep D4.
	 */
	static boolean appliesPerBillingMonth(RuleSnapshot rule) {
		return rule.valueType() == DiscountValueType.FIXED && (rule.type() == DiscountType.BUNDLE_AMOUNT_OFF
				|| rule.type() == DiscountType.DISCOUNT_CODE);
	}

	/**
	 * D4a: the eligible lines are grouped by billing month (MONTHLY lines) plus
	 * one group for lines without a month (course / module / lesson). Every
	 * group whose own lines meet the selection mode gets min(value, the group's
	 * untaken total), allocated within that group (D4 rounding). The whole
	 * order has already qualified (selection mode + minimum purchase).
	 * @return {@code null} when no single group qualifies (e.g. an "All"
	 * bundle bought in different months) - the amount then applies once per
	 * order, as D4.
	 */
	static Map<Integer, BigDecimal> perBillingMonthAllocation(RuleSnapshot rule, List<CartLine> eligible,
			List<CartLine> untaken) {
		Map<YearMonth, List<CartLine>> eligibleByMonth = groupByBillingMonth(eligible);
		Map<YearMonth, List<CartLine>> untakenByMonth = groupByBillingMonth(untaken);
		boolean anyGroupQualifies = false;
		Map<Integer, BigDecimal> allocation = new TreeMap<>();
		for (Map.Entry<YearMonth, List<CartLine>> group : eligibleByMonth.entrySet()) {
			if (!selectionMet(rule, group.getValue())) {
				continue;
			}
			anyGroupQualifies = true;
			List<CartLine> open = untakenByMonth.getOrDefault(group.getKey(), List.of());
			allocation.putAll(DiscountAllocator.allocate(amountFor(rule, sum(open)), open));
		}
		return anyGroupQualifies ? new LinkedHashMap<>(allocation) : null;
	}

	/** Lines by billing month; lines without one share the {@code null} key. Months ascending, {@code null} last. */
	private static Map<YearMonth, List<CartLine>> groupByBillingMonth(List<CartLine> lines) {
		Map<YearMonth, List<CartLine>> groups = new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder()));
		for (CartLine line : lines) {
			groups.computeIfAbsent(line.period(), key -> new ArrayList<>()).add(line);
		}
		return groups;
	}

	private static BigDecimal sum(List<CartLine> lines) {
		return lines.stream().map(line -> money(line.unitAmount())).reduce(ZERO, BigDecimal::add);
	}

	/** FIXED: min(value, eligible total); PERCENTAGE: round_half_up(total x p / 100, 2) (D4). */
	static BigDecimal amountFor(RuleSnapshot rule, BigDecimal eligibleTotal) {
		if (eligibleTotal.signum() <= 0 || rule.value() == null) {
			return ZERO;
		}
		if (rule.valueType() == DiscountValueType.PERCENTAGE) {
			return eligibleTotal.multiply(rule.value()).divide(HUNDRED, 2, RoundingMode.HALF_UP).min(eligibleTotal);
		}
		return money(rule.value()).min(eligibleTotal);
	}

	/**
	 * Selection mode (count of DISTINCT required courses among {@code
	 * eligibleLines}) and minimum purchase (D8: sum of unit amounts of {@code
	 * purchaseBase}, the order's non-reward lines).
	 */
	static boolean qualifies(RuleSnapshot rule, List<CartLine> eligibleLines, List<CartLine> purchaseBase) {
		if (rule.minPurchaseAmount() != null) {
			BigDecimal subtotal = purchaseBase.stream()
				.filter(line -> !line.reward())
				.map(line -> money(line.unitAmount()))
				.reduce(ZERO, BigDecimal::add);
			if (subtotal.compareTo(rule.minPurchaseAmount()) < 0) {
				return false;
			}
		}
		return selectionMet(rule, eligibleLines);
	}

	/** The rule's course selection mode, counted in DISTINCT required courses among {@code eligibleLines}. */
	static boolean selectionMet(RuleSnapshot rule, List<CartLine> eligibleLines) {
		int required = rule.requiredCourseIds().size();
		if (required == 0) {
			return false;
		}
		int count = qualifyingCourses(rule, eligibleLines).size();
		return switch (rule.selectionMode()) {
			case ALL -> count == required;
			case ANY -> count >= 1;
			case MIN_COUNT -> rule.minCount() != null && count >= rule.minCount();
		};
	}

	/** The rule's required courses that have at least one (non-reward) line in {@code lines}, in rule order. */
	static List<UUID> qualifyingCourses(RuleSnapshot rule, List<CartLine> lines) {
		Set<UUID> present = new HashSet<>();
		lines.stream().filter(line -> !line.reward()).forEach(line -> present.add(line.courseId()));
		return rule.requiredCourseIds().stream().filter(present::contains).toList();
	}

	/** Student-facing explanation of an applied discount (only the applied rule's own terms). */
	public String explanation(RuleSnapshot rule) {
		return explanation(rule.type(), rule.valueType(), rule.value(), rule.currency(), rule.code(),
				rule.freeMonths());
	}

	/**
	 * Same text from snapshot fields (an order's {@code student_order_discount});
	 * {@code freeMonths} may be {@code null} there.
	 */
	public static String explanation(DiscountType type, DiscountValueType valueType, BigDecimal value,
			String currency, String code, Integer freeMonths) {
		String label = valueLabel(valueType, value, currency);
		return switch (type) {
			case BUNDLE_FREE_COURSE -> freeMonths == null ? "Free course months for buying the required courses"
					: "Free course for " + freeMonths + (freeMonths == 1 ? " month" : " months")
							+ " for buying the required courses";
			case ADVANCE_PAYMENT -> label + " off months paid in advance";
			case BUNDLE_AMOUNT_OFF -> label + " off for buying these courses together";
			case DISCOUNT_CODE -> "Code " + code + ": " + label + " off";
		};
	}

	/** "10%" or "LKR 200.00". */
	static String valueLabel(RuleSnapshot rule) {
		return valueLabel(rule.valueType(), rule.value(), rule.currency());
	}

	static String valueLabel(DiscountValueType valueType, BigDecimal value, String currency) {
		if (value == null) {
			return "";
		}
		if (valueType == DiscountValueType.PERCENTAGE) {
			return value.stripTrailingZeros().toPlainString() + "%";
		}
		return currency + " " + money(value).toPlainString();
	}

	static BigDecimal money(BigDecimal value) {
		return value.setScale(2, RoundingMode.HALF_UP);
	}

}
