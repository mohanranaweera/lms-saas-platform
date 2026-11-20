package com.lms.paymentmanagement.discount.service;

import com.lms.common.error.ApplicationException;
import com.lms.paymentmanagement.discount.config.DiscountProperties;
import com.lms.paymentmanagement.discount.domain.DiscountRedemption;
import com.lms.paymentmanagement.discount.domain.DiscountRule;
import com.lms.paymentmanagement.discount.domain.DiscountRuleVersion;
import com.lms.paymentmanagement.discount.domain.RedemptionStatus;
import com.lms.paymentmanagement.discount.domain.StudentOrderDiscount;
import com.lms.paymentmanagement.discount.repository.DiscountRedemptionRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleRepository;
import com.lms.paymentmanagement.discount.repository.DiscountRuleVersionRepository;
import com.lms.paymentmanagement.discount.repository.StudentOrderDiscountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D12 redemption lifecycle hooks, each run INSIDE the transaction of the
 * payment event it belongs to (never a separate, skippable call):
 * <ul>
 * <li>{@link #holdForEvidence} - new payment evidence. A lapsed hold is re-taken
 * only after re-validation under the rule row lock (owner decision 2026-10-21,
 * "re-validate"): the rule must still be active, not archived and inside its
 * (current version's) validity window at the tenant's today; the capacity is
 * compared with the Maximum Uses of the version recorded ON THE ORDER; and the
 * student must not hold another counting redemption of the rule - else 409 {@code
 * DISCOUNT_NO_LONGER_AVAILABLE}. Then: a gateway payment holds for the
 * configured gateway TTL (default 48h from payment start); a bank slip holds
 * without expiry while it is open (staff resolve it).</li>
 * <li>{@link #restartHold} - payment / slip rejected: the hold lapses 48h from
 * now.</li>
 * <li>{@link #consume} - payment confirmed / slip approved: CONSUMED,
 * unconditionally (even when the hold had lapsed) - a verified confirmation
 * never fails here.</li>
 * </ul>
 * All three are idempotent (one row per (order, rule); a CONSUMED row never
 * moves again) and no-ops for orders without discounts. Refunds and chargebacks
 * never touch redemptions (a use is not restored).
 */
@Service
public class DiscountRedemptionService {

	public static final String DISCOUNT_NO_LONGER_AVAILABLE = "DISCOUNT_NO_LONGER_AVAILABLE";

	private static final Logger log = LoggerFactory.getLogger(DiscountRedemptionService.class);

	/** Which kind of payment evidence is being opened (decides the hold's expiry). */
	public enum EvidenceKind {

		/** A new PENDING gateway payment: hold for {@code app.discount.gateway-hold-ttl}. */
		GATEWAY,

		/** A new bank slip: hold without expiry while the slip is open. */
		SLIP

	}

	private final DiscountRedemptionRepository redemptionRepository;

	private final DiscountRuleRepository ruleRepository;

	private final DiscountRuleVersionRepository versionRepository;

	private final StudentOrderDiscountRepository orderDiscountRepository;

	private final DiscountClock clock;

	private final DiscountProperties properties;

	public DiscountRedemptionService(DiscountRedemptionRepository redemptionRepository,
			DiscountRuleRepository ruleRepository, DiscountRuleVersionRepository versionRepository,
			StudentOrderDiscountRepository orderDiscountRepository, DiscountClock clock,
			DiscountProperties properties) {
		this.redemptionRepository = redemptionRepository;
		this.ruleRepository = ruleRepository;
		this.versionRepository = versionRepository;
		this.orderDiscountRepository = orderDiscountRepository;
		this.clock = clock;
		this.properties = properties;
	}

	/** 409 - an order's discount hold lapsed and can no longer be re-taken. */
	public static class DiscountNoLongerAvailableException extends ApplicationException {

		public DiscountNoLongerAvailableException() {
			super(HttpStatus.CONFLICT, DISCOUNT_NO_LONGER_AVAILABLE,
					"A discount on this order is no longer available - please start a new payment");
		}

	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void holdForEvidence(UUID orderId, EvidenceKind kind) {
		Instant now = clock.now();
		// Sorted by rule id (repository order) -> rule locks always in id order.
		List<DiscountRedemption> redemptions = redemptionRepository.findAllByOrderId(orderId);
		if (redemptions.isEmpty()) {
			return;
		}
		Map<UUID, UUID> versionByRule = orderVersions(orderId);
		LocalDate today = null;
		for (DiscountRedemption redemption : redemptions) {
			if (redemption.getStatus() != RedemptionStatus.RESERVED) {
				continue;
			}
			if (!redemption.countsAt(now)) {
				DiscountRule rule = ruleRepository.lockById(redemption.getRuleId())
					.orElseThrow(DiscountNoLongerAvailableException::new);
				if (today == null) {
					today = clock.today();
				}
				requireRetakable(redemption, Optional.of(rule), versionByRule.get(rule.getId()), today, now);
			}
			boolean changed = kind == EvidenceKind.GATEWAY
					? redemption.holdUntil(now, now.plus(properties.getGatewayHoldTtl()))
					: redemption.holdWithoutExpiry(now);
			if (changed) {
				redemptionRepository.save(redemption);
			}
		}
	}

	/**
	 * Unlocked pre-check (before a gateway call / storage write) of {@link
	 * #holdForEvidence}: 409 when a lapsed hold could not be re-taken now. The
	 * authoritative, locked check still runs in the evidence transaction.
	 */
	@Transactional(readOnly = true)
	public void requireHoldRetakable(UUID orderId) {
		Instant now = clock.now();
		List<DiscountRedemption> redemptions = redemptionRepository.findAllByOrderId(orderId);
		if (redemptions.isEmpty()) {
			return;
		}
		Map<UUID, UUID> versionByRule = orderVersions(orderId);
		LocalDate today = null;
		for (DiscountRedemption redemption : redemptions) {
			if (redemption.getStatus() != RedemptionStatus.RESERVED || redemption.countsAt(now)) {
				continue;
			}
			if (today == null) {
				today = clock.today();
			}
			requireRetakable(redemption, ruleRepository.findById(redemption.getRuleId()),
					versionByRule.get(redemption.getRuleId()), today, now);
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void restartHold(UUID orderId) {
		Instant now = clock.now();
		for (DiscountRedemption redemption : redemptionRepository.findAllByOrderId(orderId)) {
			if (redemption.restartExpiry(now, now.plus(DiscountCheckoutService.HOLD_TTL))) {
				redemptionRepository.save(redemption);
			}
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void consume(UUID orderId) {
		Instant now = clock.now();
		for (DiscountRedemption redemption : redemptionRepository.findAllByOrderId(orderId)) {
			if (redemption.consume(now)) {
				redemptionRepository.save(redemption);
			}
		}
	}

	/** Read-only view for tests / admin counters. */
	@Transactional(readOnly = true)
	public List<DiscountRedemption> forOrder(UUID orderId) {
		return redemptionRepository.findAllByOrderId(orderId);
	}

	/** rule id -> the rule version recorded on the order's immutable snapshot. */
	private Map<UUID, UUID> orderVersions(UUID orderId) {
		Map<UUID, UUID> versions = new HashMap<>();
		for (StudentOrderDiscount snapshot : orderDiscountRepository.findAllByOrderId(orderId)) {
			versions.put(snapshot.getRuleId(), snapshot.getRuleVersionId());
		}
		return versions;
	}

	/**
	 * Re-validation of a LAPSED hold before it is re-taken (owner decision
	 * 2026-10-21): 409 when the rule is gone / archived / inactive, today is
	 * outside the rule's validity window (its CURRENT version - an edit that
	 * ends a promotion early also stops re-takes), the Maximum Uses of the
	 * version recorded on the order is reached, or - for a code / capped rule -
	 * the student meanwhile holds another counting redemption of it (one use per student).
	 */
	private void requireRetakable(DiscountRedemption redemption, Optional<DiscountRule> found, UUID versionId,
			LocalDate today, Instant now) {
		String reason = null;
		DiscountRule rule = found.orElse(null);
		DiscountRuleVersion version = versionId == null ? null : versionRepository.findById(versionId).orElse(null);
		DiscountRuleVersion current = rule == null || rule.getCurrentVersionId() == null ? null
				: rule.getCurrentVersionId().equals(versionId) ? version
						: versionRepository.findById(rule.getCurrentVersionId()).orElse(null);
		if (rule == null || version == null || current == null) {
			reason = "rule_missing";
		}
		else if (rule.isArchived()) {
			reason = "archived";
		}
		else if (!rule.isActive()) {
			reason = "inactive";
		}
		else if (today.isBefore(current.getValidFrom()) || today.isAfter(current.getValidTo())) {
			reason = "outside_validity";
		}
		else if (version.getMaxUses() != null
				&& redemptionRepository.countInUse(rule.getId(), now) >= version.getMaxUses()) {
			reason = "usage_limit";
		}
		else if (RuleSnapshot.oneUsePerStudent(rule.getDiscountType(), version.getMaxUses())
				&& redemptionRepository.findAllByStudentIdAndRuleIdIn(redemption.getStudentId(), List.of(rule.getId()))
			.stream()
			.anyMatch(other -> !other.getId().equals(redemption.getId()) && other.countsAt(now))) {
			reason = "already_used_by_student";
		}
		if (reason != null) {
			log.atInfo()
				.setMessage("discount.redemption_retake_refused")
				.addKeyValue("orderId", redemption.getOrderId())
				.addKeyValue("ruleId", redemption.getRuleId())
				.addKeyValue("reason", reason)
				.log();
			throw new DiscountNoLongerAvailableException();
		}
	}

}
