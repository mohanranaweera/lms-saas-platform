package com.lms.paymentmanagement.discount.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.paymentmanagement.discount.domain.DiscountRuleVersion;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped, insert-only access to {@code discount_rule_version} (V92, D13).
 * The entity is {@code @Immutable}; deletes are refused.
 */
public interface DiscountRuleVersionRepository extends AppendOnlyTenantAwareRepository<DiscountRuleVersion, UUID> {

	default List<DiscountRuleVersion> findAllByIdIn(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("id").in(ids));
	}

	default long countByRuleId(UUID ruleId) {
		return count((root, query, cb) -> cb.equal(root.get("ruleId"), ruleId));
	}

	default List<DiscountRuleVersion> findAllByRuleId(UUID ruleId) {
		return findAll((root, query, cb) -> cb.equal(root.get("ruleId"), ruleId), Sort.by("versionNo"));
	}

}
