package com.lms.financeexpensemanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.financeexpensemanagement.domain.FinanceAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** FIN-01: finance accounts are archived, never deleted (postings reference them). */
public interface FinanceAccountRepository extends AppendOnlyTenantAwareRepository<FinanceAccount, UUID> {

	default List<FinanceAccount> findAllOrdered(boolean includeArchived) {
		Sort sort = Sort.by(Sort.Direction.ASC, "name");
		if (includeArchived) {
			return findAll(sort);
		}
		return findAll((root, query, cb) -> cb.isFalse(root.get("archived")), sort);
	}

	default boolean existsByNameIgnoreCaseExcluding(String name, UUID excludeId) {
		return exists((root, query, cb) -> {
			var nameMatch = cb.equal(cb.lower(root.get("name")), name.toLowerCase());
			return (excludeId == null) ? nameMatch : cb.and(nameMatch, cb.notEqual(root.get("id"), excludeId));
		});
	}

}
