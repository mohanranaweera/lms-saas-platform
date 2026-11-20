package com.lms.paymentmanagement.discount.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.paymentmanagement.discount.domain.DiscountRedemption;
import com.lms.paymentmanagement.discount.domain.RedemptionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped access to {@code discount_redemption} (V92, D12). Rows are
 * never deleted; only the status/expiry columns move forward through {@code
 * save}.
 */
public interface DiscountRedemptionRepository extends AppendOnlyTenantAwareRepository<DiscountRedemption, UUID> {

	default List<DiscountRedemption> findAllByOrderId(UUID orderId) {
		return findAll((root, query, cb) -> cb.equal(root.get("orderId"), orderId), Sort.by("ruleId"));
	}

	/** Capacity in use: CONSUMED + RESERVED whose {@code expires_at} is null or after {@code now}. */
	default long countInUse(UUID ruleId, Instant now) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("ruleId"), ruleId),
				cb.or(cb.equal(root.get("status"), RedemptionStatus.CONSUMED), cb.isNull(root.get("expiresAt")),
						cb.greaterThan(root.get("expiresAt"), now))));
	}

	/**
	 * The current tenant's redemptions of {@code studentId} for {@code ruleIds}
	 * (any status / expiry; callers filter with {@link
	 * DiscountRedemption#countsAt}) - the one-use-per-student check.
	 */
	default List<DiscountRedemption> findAllByStudentIdAndRuleIdIn(UUID studentId, Collection<UUID> ruleIds) {
		if (ruleIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				root.get("ruleId").in(ruleIds)));
	}

	default long countByRuleIdAndStatus(UUID ruleId, RedemptionStatus status) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("ruleId"), ruleId),
				cb.equal(root.get("status"), status)));
	}

	/** All rows of the given rules (admin usage counters). */
	default List<DiscountRedemption> findAllByRuleIdIn(Collection<UUID> ruleIds) {
		if (ruleIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("ruleId").in(ruleIds));
	}

}
