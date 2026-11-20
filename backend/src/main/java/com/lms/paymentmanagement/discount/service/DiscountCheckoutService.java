package com.lms.paymentmanagement.discount.service;

import com.lms.enrollmentmanagement.api.CoverageKeys;
import com.lms.paymentmanagement.discount.domain.DiscountRedemption;
import com.lms.paymentmanagement.discount.domain.DiscountRule;
import com.lms.paymentmanagement.discount.domain.DiscountType;
import com.lms.paymentmanagement.discount.domain.StudentOrderDiscount;
import com.lms.paymentmanagement.discount.repository.DiscountRedemptionRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleVersionCourseRepository;
import com.lms.paymentmanagement.discount.repository.StudentOrderDiscountRepository;
import com.lms.paymentmanagement.discount.service.DiscountPricing.AppliedDiscount;
import com.lms.paymentmanagement.discount.service.DiscountPricing.CodeStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Database side of discount pricing for the checkout composer (spec §2/§3,
 * D12): candidate rules for a cart, the order-placement locks, and the
 * immutable order snapshot + reservation. Everything is tenant-scoped through
 * the structural repositories; nothing here reads a tenant id from a request.
 * Runs inside the caller's (composer's) transaction.
 */
@Service
public class DiscountCheckoutService {

	/** D12: an order's hold lapses this long after placement / evidence rejection. */
	public static final Duration HOLD_TTL = Duration.ofHours(48);

	private static final Logger log = LoggerFactory.getLogger(DiscountCheckoutService.class);

	private final DiscountRuleRepository ruleRepository;

	private final DiscountRuleLoader ruleLoader;

	private final DiscountRuleVersionCourseRepository versionCourseRepository;

	private final DiscountRedemptionRepository redemptionRepository;

	private final StudentOrderDiscountRepository orderDiscountRepository;

	private final ObjectMapper objectMapper;

	public DiscountCheckoutService(DiscountRuleRepository ruleRepository, DiscountRuleLoader ruleLoader,
			DiscountRuleVersionCourseRepository versionCourseRepository,
			DiscountRedemptionRepository redemptionRepository, StudentOrderDiscountRepository orderDiscountRepository,
			ObjectMapper objectMapper) {
		this.ruleRepository = ruleRepository;
		this.ruleLoader = ruleLoader;
		this.versionCourseRepository = versionCourseRepository;
		this.redemptionRepository = redemptionRepository;
		this.orderDiscountRepository = orderDiscountRepository;
		this.objectMapper = objectMapper;
	}

	/**
	 * Available rules for a cart.
	 * @param freeRules gated BUNDLE_FREE_COURSE rules.
	 * @param valueRules gated ADVANCE_PAYMENT / BUNDLE_AMOUNT_OFF rules + the
	 * entered code's rule when it passed the gate.
	 * @param code the normalized entered code ({@code null} when none).
	 * @param codeRuleId the entered code's rule id when it is in {@code valueRules}.
	 * @param codeGateStatus why the entered code cannot apply at all - the
	 * INTERNAL reason (INVALID / DISABLED / EXPIRED / NOT_YET_VALID /
	 * NOT_ELIGIBLE / USAGE_LIMIT_REACHED / ALREADY_USED), else {@code null};
	 * students see {@link CodeStatus#studentFacing()}.
	 */
	public record Candidates(List<RuleSnapshot> freeRules, List<RuleSnapshot> valueRules, String code,
			UUID codeRuleId, CodeStatus codeGateStatus) {

	}

	/**
	 * Owner decision 2026-10-21 (hold abuse): a code or capped rule ({@link
	 * RuleSnapshot#oneUsePerStudent()}) the student already holds a counting
	 * redemption of (RESERVED and not lapsed, or CONSUMED) is not a candidate
	 * for this student - a value rule is not applied, a free-course
	 * reward is not offered, and an entered code is {@code ALREADY_USED}
	 * (shown as {@code NOT_ELIGIBLE}). Order placement reads this AFTER {@link
	 * #lockRulesForCart}, so it is race-safe per rule.
	 * @param studentId the authenticated student (never a request value).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Candidates candidates(String cartCurrency, String rawCode, UUID studentId, LocalDate today, Instant now) {
		String code = DiscountPricingService.normalizeCode(rawCode);
		List<DiscountRule> headers = new ArrayList<>(ruleRepository.findAllLiveActive());
		DiscountRule codeHeader = null;
		CodeStatus codeGateStatus = null;
		if (code != null) {
			if (!DiscountPricingService.isWellFormedCode(code)) {
				codeGateStatus = CodeStatus.INVALID;
			}
			else {
				Optional<DiscountRule> found = ruleRepository.findLiveByCode(code);
				if (found.isEmpty() || found.get().getDiscountType() != DiscountType.DISCOUNT_CODE) {
					codeGateStatus = CodeStatus.INVALID;
				}
				else {
					codeHeader = found.get();
					DiscountRule header = codeHeader;
					if (headers.stream().noneMatch(rule -> rule.getId().equals(header.getId()))) {
						headers.add(codeHeader);
					}
				}
			}
		}
		List<RuleSnapshot> freeRules = new ArrayList<>();
		List<RuleSnapshot> valueRules = new ArrayList<>();
		UUID codeRuleId = null;
		List<DiscountRuleLoader.LoadedRule> loadedRules = ruleLoader.load(headers);
		Set<UUID> usedByStudent = countingRuleIds(studentId,
				loadedRules.stream().map(loaded -> loaded.rule().getId()).toList(), now);
		for (DiscountRuleLoader.LoadedRule loaded : loadedRules) {
			RuleSnapshot snapshot = loaded.snapshot();
			boolean isCodeRule = codeHeader != null && codeHeader.getId().equals(loaded.rule().getId());
			if (snapshot.type() == DiscountType.DISCOUNT_CODE && !isCodeRule) {
				continue;
			}
			Optional<CodeStatus> failure = DiscountPricingService.gateFailure(snapshot, loaded.rule().isActive(),
					today, cartCurrency, true);
			if (failure.isEmpty() && snapshot.oneUsePerStudent() && usedByStudent.contains(snapshot.ruleId())) {
				failure = Optional.of(CodeStatus.ALREADY_USED);
			}
			if (failure.isEmpty() && snapshot.maxUses() != null
					&& redemptionRepository.countInUse(snapshot.ruleId(), now) >= snapshot.maxUses()) {
				failure = Optional.of(CodeStatus.USAGE_LIMIT_REACHED);
			}
			if (failure.isPresent()) {
				if (isCodeRule) {
					codeGateStatus = failure.get();
					// The real reason stays internal; the student sees studentFacing().
					log.atDebug()
						.setMessage("discount.code_not_applicable")
						.addKeyValue("ruleId", snapshot.ruleId())
						.addKeyValue("reason", failure.get())
						.log();
				}
				continue;
			}
			if (snapshot.type() == DiscountType.BUNDLE_FREE_COURSE) {
				freeRules.add(snapshot);
			}
			else {
				valueRules.add(snapshot);
				if (isCodeRule) {
					codeRuleId = snapshot.ruleId();
				}
			}
		}
		return new Candidates(List.copyOf(freeRules), List.copyOf(valueRules), code, codeRuleId, codeGateStatus);
	}

	/**
	 * Coverage keys of every month an accepted free-course rule could add
	 * (its current version's window for each reward course), so order
	 * placement can take them with the cart's other coverage locks, in one
	 * sorted acquisition, BEFORE pricing. Stops once more than {@code maxKeys}
	 * keys were collected (the caller then refuses the order).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Set<String> rewardLockKeys(Collection<UUID> acceptedRuleIds, YearMonth currentMonth, int maxKeys) {
		Set<String> keys = new TreeSet<>();
		for (Object[] row : versionCourseRepository.findRewardWindows(acceptedRuleIds)) {
			UUID courseId = (UUID) row[0];
			Integer months = (Integer) row[1];
			if (courseId == null || months == null) {
				continue;
			}
			for (int i = 0; i < Math.min(months, 24); i++) {
				YearMonth period = currentMonth.plusMonths(i);
				keys.add(CoverageKeys.monthly(courseId, period.getYear(), period.getMonthValue()));
				if (keys.size() > maxKeys) {
					// Bounded: the caller refuses the order (fail closed) - never an
					// unbounded advisory-lock acquisition.
					return keys;
				}
			}
		}
		return keys;
	}

	/**
	 * D12 + owner decision 2026-10-21: locks (FOR UPDATE, id order) every code
	 * or capped rule that could apply to a cart over {@code courseIds}, so the
	 * capacity and one-use-per-student reads of the re-quote and the
	 * reservation written after them are serialized per rule. Called after the
	 * coverage locks and before pricing.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockRulesForCart(Collection<UUID> courseIds, String rawCode) {
		String code = DiscountPricingService.normalizeCode(rawCode);
		ruleRepository.lockRuleIdsForCart(courseIds, DiscountPricingService.isWellFormedCode(code) ? code : null);
	}

	/**
	 * Writes the immutable snapshot (one {@code student_order_discount} per
	 * applied rule) and one RESERVED redemption per applied rule ({@code
	 * expires_at = now + 48h}), in the order-placement transaction. Re-checks,
	 * under each applied rule's row lock, that the student holds no other
	 * counting redemption of it (409 {@code DISCOUNT_NO_LONGER_AVAILABLE}), and
	 * that each discount's allocation sums to its amount.
	 * @return rule id -> {@code student_order_discount.id} (for the line links).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Map<UUID, UUID> persistOrderDiscounts(UUID tenantId, UUID orderId, UUID studentId,
			List<AppliedDiscount> applied, Instant now) {
		Map<UUID, UUID> idsByRule = new LinkedHashMap<>();
		if (applied.isEmpty()) {
			return idsByRule;
		}
		// Every code / capped rule is row-locked (a no-op re-lock when
		// lockRulesForCart already holds it), in id order, before the
		// one-use-per-student check; uncapped automatic rules are not capped per student.
		List<UUID> ruleIds = applied.stream()
			.map(AppliedDiscount::rule)
			.filter(RuleSnapshot::oneUsePerStudent)
			.map(RuleSnapshot::ruleId)
			.distinct()
			.sorted(Comparator.comparing(UUID::toString))
			.toList();
		for (UUID ruleId : ruleIds) {
			ruleRepository.lockById(ruleId)
				.orElseThrow(DiscountRedemptionService.DiscountNoLongerAvailableException::new);
		}
		if (!countingRuleIds(studentId, ruleIds, now).isEmpty()) {
			log.atWarn()
				.setMessage("discount.per_student_cap_refused")
				.addKeyValue("orderId", orderId)
				.addKeyValue("studentId", studentId)
				.log();
			throw new DiscountRedemptionService.DiscountNoLongerAvailableException();
		}
		for (AppliedDiscount discount : applied) {
			RuleSnapshot rule = discount.rule();
			BigDecimal allocated = discount.allocation()
				.values()
				.stream()
				.reduce(DiscountPricingService.ZERO, BigDecimal::add);
			if (discount.amount().signum() <= 0 || allocated.compareTo(discount.amount()) != 0) {
				// Invariant (student_order_discount.amount = sum of its lines): fail closed.
				throw new IllegalStateException(
						"Discount allocation does not sum to the applied amount for rule " + rule.ruleId());
			}
			boolean free = rule.type() == DiscountType.BUNDLE_FREE_COURSE;
			StudentOrderDiscount saved = orderDiscountRepository.save(new StudentOrderDiscount(tenantId, orderId,
					rule.ruleId(), rule.versionId(), rule.type(), rule.name(),
					rule.type() == DiscountType.DISCOUNT_CODE ? rule.code() : null, free ? null : rule.valueType(),
					free ? null : rule.value(), discount.amount(), qualificationJson(discount), now));
			idsByRule.put(rule.ruleId(), saved.getId());
			redemptionRepository.save(DiscountRedemption.reserve(tenantId, rule.ruleId(), orderId, studentId, now,
					now.plus(HOLD_TTL)));
		}
		return idsByRule;
	}

	/** Rule ids among {@code ruleIds} the student holds a counting redemption of at {@code now}. */
	Set<UUID> countingRuleIds(UUID studentId, Collection<UUID> ruleIds, Instant now) {
		if (studentId == null || ruleIds.isEmpty()) {
			return Set.of();
		}
		return redemptionRepository.findAllByStudentIdAndRuleIdIn(studentId, ruleIds)
			.stream()
			.filter(redemption -> redemption.countsAt(now))
			.map(DiscountRedemption::getRuleId)
			.collect(Collectors.toSet());
	}

	@Transactional(readOnly = true)
	public List<StudentOrderDiscount> orderDiscounts(UUID orderId) {
		return orderDiscountRepository.findAllByOrderId(orderId);
	}

	private String qualificationJson(AppliedDiscount discount) {
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("qualifyingCourseIds", discount.qualification().qualifyingCourseIds());
		json.put("advancePeriods", discount.qualification().advancePeriods());
		json.put("rewardMonths", discount.qualification().rewardMonths());
		json.put("rewardCourseIds", discount.qualification().rewardCourseIds());
		json.put("lineNos", discount.lineNos());
		try {
			return objectMapper.writeValueAsString(json);
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Could not serialize the discount qualification", ex);
		}
	}

}
