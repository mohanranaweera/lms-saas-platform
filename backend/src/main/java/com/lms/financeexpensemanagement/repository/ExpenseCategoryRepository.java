package com.lms.financeexpensemanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.financeexpensemanagement.domain.ExpenseCategory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006 - every inherited read/write is structurally
 * filtered to the resolved tenant by {@code TenantAwareRepositoryImpl}; no
 * method here accepts a tenant id. Categories are never deleted (expenses
 * reference them; financial history is never deleted), so every delete-shaped
 * method fails loudly - retire a category with {@code archive()} instead.
 */
public interface ExpenseCategoryRepository extends TenantAwareRepository<ExpenseCategory, UUID> {

	default List<ExpenseCategory> findAllOrdered(boolean includeArchived) {
		Sort sort = Sort.by(Sort.Direction.ASC, "name");
		if (includeArchived) {
			return findAll(sort);
		}
		return findAll((root, query, cb) -> cb.isFalse(root.get("archived")), sort);
	}

	/** Case-insensitive, tenant-scoped name clash check (friendly pre-check for {@code uq_expense_category_tenant_name}). */
	default boolean existsByNameIgnoreCaseExcluding(String name, UUID excludeId) {
		return exists((root, query, cb) -> {
			var nameMatch = cb.equal(cb.lower(root.get("name")), name.toLowerCase());
			return (excludeId == null) ? nameMatch : cb.and(nameMatch, cb.notEqual(root.get("id"), excludeId));
		});
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("expense_category rows are never deleted - archive instead");
	}

	@Override
	default void delete(ExpenseCategory entity) {
		throw new UnsupportedOperationException("expense_category rows are never deleted - archive instead");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("expense_category rows are never deleted - archive instead");
	}

	@Override
	default void deleteAll(Iterable<? extends ExpenseCategory> entities) {
		throw new UnsupportedOperationException("expense_category rows are never deleted - archive instead");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("expense_category rows are never deleted - archive instead");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("expense_category rows are never deleted - archive instead");
	}

	@Override
	default void deleteAllInBatch(Iterable<ExpenseCategory> entities) {
		throw new UnsupportedOperationException("expense_category rows are never deleted - archive instead");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("expense_category rows are never deleted - archive instead");
	}

}
