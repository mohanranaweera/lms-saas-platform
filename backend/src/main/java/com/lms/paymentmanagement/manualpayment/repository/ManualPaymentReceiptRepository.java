package com.lms.paymentmanagement.manualpayment.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.paymentmanagement.manualpayment.domain.ManualPaymentReceipt;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped {@code manual_payment_receipt} access (ADR-030). Every finder
 * is a {@code default} method on the inherited, structurally tenant-filtered
 * {@code findAll}/{@code findOne} - no method accepts a tenant id. Insert-only
 * (V93 trigger): {@code save} is called once per new receipt; deletes are
 * refused.
 */
public interface ManualPaymentReceiptRepository extends AppendOnlyTenantAwareRepository<ManualPaymentReceipt, UUID> {

	/** The receipt recorded with this idempotency key in the current tenant (M7 replay). */
	default Optional<ManualPaymentReceipt> findByIdempotencyKey(UUID idempotencyKey) {
		return findOne((root, query, cb) -> cb.equal(root.get("idempotencyKey"), idempotencyKey));
	}

	default List<ManualPaymentReceipt> findAllByOrderIdIn(Collection<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("orderId").in(orderIds));
	}

	/** RPT-02: receipts of {@code paymentIds} (tenant-scoped). */
	default List<ManualPaymentReceipt> findAllByPaymentIdIn(Collection<UUID> paymentIds) {
		if (paymentIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("paymentId").in(paymentIds));
	}

	/** RPT-02: number of receipts received in {@code [from, to]} (tenant-scoped count, no rows loaded). */
	default long countReceivedBetween(LocalDate from, LocalDate to) {
		return count((root, query, cb) -> cb.between(root.get("receivedOn"), from, to));
	}

	/** RPT-02: number of receipts in {@code [from, to]} for the supplied payment ids. Tenant-scoped. */
	default long countReceivedBetweenForPaymentIds(LocalDate from, LocalDate to, Collection<UUID> paymentIds) {
		if (paymentIds.isEmpty()) {
			return 0;
		}
		return count((root, query, cb) -> cb.and(cb.between(root.get("receivedOn"), from, to),
				root.get("paymentId").in(paymentIds)));
	}

	/** RPT-02: payment ids (only that column) of receipts received in {@code [from, to]}. Tenant-scoped. */
	default List<UUID> findPaymentIdsReceivedBetween(LocalDate from, LocalDate to) {
		return findAttributeValues((root, query, cb) -> cb.between(root.get("receivedOn"), from, to), "paymentId",
				UUID.class);
	}

	/**
	 * Transaction-scoped Postgres advisory lock on a caller-built key that
	 * already contains the context tenant ({@code manual-receipt:<tenant>:<key>}).
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireLock(@Param("lockKey") String lockKey);

}
