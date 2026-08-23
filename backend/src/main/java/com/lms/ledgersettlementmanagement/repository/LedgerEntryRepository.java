package com.lms.ledgersettlementmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006. {@code ledger_entry} is fully append-only, so
 * (mirroring {@code CoursePriceHistoryRepository}'s exact pattern) every
 * delete-shaped method inherited from {@link TenantAwareRepository}/{@code
 * JpaRepository} is overridden below to fail loudly - PAY-3's own
 * acceptance criterion ("no repository method exposes delete/deleteById").
 */
public interface LedgerEntryRepository extends TenantAwareRepository<LedgerEntry, UUID> {

	/**
	 * Locates the {@code PAYMENT_CONFIRMED} entry for a payment, to reverse
	 * when a refund is processed. Assumes exactly one such entry exists per
	 * payment (PAY-2/PAY-3's own invariant: exactly one ledger entry per
	 * confirmed payment).
	 */
	default Optional<LedgerEntry> findConfirmedEntryForPayment(UUID paymentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("paymentId"), paymentId),
				cb.equal(root.get("entryType"), LedgerEntryType.PAYMENT_CONFIRMED)));
	}

	/** Student's own Payment History read pattern - entries across all of that student's orders. */
	default List<LedgerEntry> findAllByOrderIdIn(List<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("orderId").in(orderIds), Sort.by(Sort.Direction.DESC, "createdAt"));
	}

	/** Tenant-admin Payment Dashboard read pattern - every entry in the caller's own tenant, paginated. */
	default Page<LedgerEntry> findAllForDashboard(Pageable pageable) {
		return findAll((root, query, cb) -> cb.conjunction(), pageable);
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("ledger_entry is append-only - no row may ever be deleted");
	}

	@Override
	default void delete(LedgerEntry entity) {
		throw new UnsupportedOperationException("ledger_entry is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("ledger_entry is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends LedgerEntry> entities) {
		throw new UnsupportedOperationException("ledger_entry is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("ledger_entry is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("ledger_entry is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<LedgerEntry> entities) {
		throw new UnsupportedOperationException("ledger_entry is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("ledger_entry is append-only - no row may ever be deleted");
	}

}
