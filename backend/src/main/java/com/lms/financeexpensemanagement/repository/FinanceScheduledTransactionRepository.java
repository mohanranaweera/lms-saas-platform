package com.lms.financeexpensemanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.financeexpensemanagement.domain.FinanceEnums.ScheduleStatus;
import com.lms.financeexpensemanagement.domain.FinanceScheduledTransaction;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

/** FIN-01: schedules are cancelled/completed, never deleted (occurrences reference them). */
public interface FinanceScheduledTransactionRepository
		extends AppendOnlyTenantAwareRepository<FinanceScheduledTransaction, UUID> {

	default Page<FinanceScheduledTransaction> search(String search, ScheduleStatus status, Pageable pageable) {
		return findAll((root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (search != null && !search.isBlank()) {
				String like = "%" + search.trim().toLowerCase() + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.get("note")), like),
						cb.like(cb.lower(root.get("name")), like)));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		}, pageable);
	}

	default List<FinanceScheduledTransaction> findActive() {
		return findAll((root, query, cb) -> cb.equal(root.get("status"), ScheduleStatus.ACTIVE));
	}

	default boolean existsActiveForAccount(UUID accountId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("status"), ScheduleStatus.ACTIVE),
				cb.or(cb.equal(root.get("fromAccountId"), accountId), cb.equal(root.get("toAccountId"), accountId))));
	}

	/**
	 * ADR-006 explicitly named cross-tenant read, used ONLY by the materialising
	 * job to learn which tenants to visit; the job then sets each tenant's context
	 * before touching any tenant-owned row.
	 */
	@Query(value = "SELECT DISTINCT s.tenant_id FROM finance_scheduled_transaction s WHERE s.status = 'ACTIVE'",
			nativeQuery = true)
	List<UUID> findTenantIdsWithActiveSchedulesAcrossTenants();

}
