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

	default List<TeacherSettlementItem> findBySettlementId(UUID settlementId) {
		return findAll((root, query, cb) -> cb.equal(root.get("settlementId"), settlementId));
	}

}
