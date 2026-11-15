package com.lms.financeexpensemanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.financeexpensemanagement.domain.FinanceEnums.OccurrenceStatus;
import com.lms.financeexpensemanagement.domain.FinanceScheduledOccurrence;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** FIN-01: occurrences are a history of what fell due; never deleted. */
public interface FinanceScheduledOccurrenceRepository
		extends AppendOnlyTenantAwareRepository<FinanceScheduledOccurrence, UUID> {

	default List<FinanceScheduledOccurrence> findBySchedule(UUID scheduleId) {
		return findAll((root, query, cb) -> cb.equal(root.get("scheduleId"), scheduleId),
				Sort.by(Sort.Direction.DESC, "dueDate"));
	}

	default Optional<FinanceScheduledOccurrence> findByScheduleAndDueDate(UUID scheduleId, LocalDate dueDate) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("scheduleId"), scheduleId),
				cb.equal(root.get("dueDate"), dueDate)));
	}

	default Optional<LocalDate> findLatestDueDate(UUID scheduleId) {
		return findBySchedule(scheduleId).stream().map(FinanceScheduledOccurrence::getDueDate).findFirst();
	}

	default List<FinanceScheduledOccurrence> findOpen() {
		return findAll((root, query, cb) -> root.get("status").in(OccurrenceStatus.DUE, OccurrenceStatus.FAILED),
				Sort.by(Sort.Direction.ASC, "dueDate"));
	}

	default long countByScheduleAndStatus(UUID scheduleId, OccurrenceStatus status) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("scheduleId"), scheduleId),
				cb.equal(root.get("status"), status)));
	}

}
