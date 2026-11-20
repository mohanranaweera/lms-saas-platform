package com.lms.financeexpensemanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.financeexpensemanagement.domain.FinanceEnums.RoutingMethod;
import com.lms.financeexpensemanagement.domain.FinancePaymentRouting;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FIN-01: routing rows are replaced as a whole set (delete + insert in one
 * transaction), so this repository deliberately keeps the inherited delete
 * methods - they only ever run against the caller's own tenant rows.
 */
public interface FinancePaymentRoutingRepository extends TenantAwareRepository<FinancePaymentRouting, UUID> {

	default Optional<FinancePaymentRouting> findByMethod(RoutingMethod method) {
		return findOne((root, query, cb) -> cb.equal(root.get("methodCode"), method));
	}

	default List<FinancePaymentRouting> findAllForAccount(UUID accountId) {
		return findAll((root, query, cb) -> cb.equal(root.get("accountId"), accountId));
	}

}
