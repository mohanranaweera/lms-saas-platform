package com.lms.ledgersettlementmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.ledgersettlementmanagement.domain.TeacherSettlementItem;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Tenant-scoped (ADR-006), append-only. No method accepts a tenant id. */
public interface TeacherSettlementItemRepository extends AppendOnlyTenantAwareRepository<TeacherSettlementItem, UUID> {

	/** Which of {@code ledgerEntryIds} are already included in some settlement. */
	default Set<UUID> findSettledLedgerEntryIds(Collection<UUID> ledgerEntryIds) {
		if (ledgerEntryIds.isEmpty()) {
			return Set.of();
		}
		return findAll((root, query, cb) -> root.get("ledgerEntryId").in(ledgerEntryIds)).stream()
			.map(TeacherSettlementItem::getLedgerEntryId)
			.collect(Collectors.toSet());
	}

	/**
	 * ADR-029 (V91): which (ledger entry, course) allocations among {@code
	 * ledgerEntryIds} are already settled, as {@link #allocationKey} strings.
	 */
	default Set<String> findSettledAllocationKeys(Collection<UUID> ledgerEntryIds) {
		if (ledgerEntryIds.isEmpty()) {
			return Set.of();
		}
		return findAll((root, query, cb) -> root.get("ledgerEntryId").in(ledgerEntryIds)).stream()
			.map(item -> allocationKey(item.getLedgerEntryId(), item.getCourseId()))
			.collect(Collectors.toSet());
	}

	static String allocationKey(UUID ledgerEntryId, UUID courseId) {
		return ledgerEntryId + ":" + courseId;
	}

	/** RPT-01: stored items of any of {@code ledgerEntryIds} (tenant-scoped). */
	default List<TeacherSettlementItem> findByLedgerEntryIds(Collection<UUID> ledgerEntryIds) {
		if (ledgerEntryIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("ledgerEntryId").in(ledgerEntryIds));
	}

	default List<TeacherSettlementItem> findBySettlementId(UUID settlementId) {
		return findAll((root, query, cb) -> cb.equal(root.get("settlementId"), settlementId));
	}

}
