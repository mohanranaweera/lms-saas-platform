package com.lms.financeexpensemanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.financeexpensemanagement.domain.Expense;
import com.lms.financeexpensemanagement.domain.ExpenseMethod;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/**
 * Tenant-scoped per ADR-006 (every read is Specification-based so the tenant
 * predicate is applied structurally; no method accepts a tenant id). {@code
 * expense} is append-only financial history - every delete-shaped method is
 * overridden to fail loudly, mirroring {@code LedgerEntryRepository}.
 */
public interface ExpenseRepository extends TenantAwareRepository<Expense, UUID> {

	/**
	 * Filtered expense list. Every argument is optional ({@code null} =
	 * unfiltered); voided rows are excluded unless {@code includeVoided}.
	 */
	default Page<Expense> search(LocalDate from, LocalDate to, UUID categoryId, ExpenseMethod method,
			boolean includeVoided, Pageable pageable) {
		return findAll(filter(from, to, categoryId, method, includeVoided), pageable);
	}

	/** Non-voided expenses dated in {@code [from, to]} (inclusive) - the report read pattern. */
	default List<Expense> findActiveDatedBetween(LocalDate from, LocalDate to) {
		return findAll(filter(from, to, null, null, false), Sort.by(Sort.Direction.ASC, "expenseDate"));
	}

	private static Specification<Expense> filter(LocalDate from, LocalDate to, UUID categoryId, ExpenseMethod method,
			boolean includeVoided) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("expenseDate"), from));
			}
			if (to != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("expenseDate"), to));
			}
			if (categoryId != null) {
				predicates.add(cb.equal(root.get("categoryId"), categoryId));
			}
			if (method != null) {
				predicates.add(cb.equal(root.get("method"), method));
			}
			if (!includeVoided) {
				predicates.add(cb.isNull(root.get("voidedAt")));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("expense is append-only - void instead of deleting");
	}

	@Override
	default void delete(Expense entity) {
		throw new UnsupportedOperationException("expense is append-only - void instead of deleting");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("expense is append-only - void instead of deleting");
	}

	@Override
	default void deleteAll(Iterable<? extends Expense> entities) {
		throw new UnsupportedOperationException("expense is append-only - void instead of deleting");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("expense is append-only - void instead of deleting");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("expense is append-only - void instead of deleting");
	}

	@Override
	default void deleteAllInBatch(Iterable<Expense> entities) {
		throw new UnsupportedOperationException("expense is append-only - void instead of deleting");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("expense is append-only - void instead of deleting");
	}

}
