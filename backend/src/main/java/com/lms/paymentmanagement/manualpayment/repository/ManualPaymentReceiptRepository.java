package com.lms.paymentmanagement.manualpayment.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.paymentmanagement.manualpayment.domain.ManualPaymentReceipt;
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

	/**
	 * Transaction-scoped Postgres advisory lock on a caller-built key that
	 * already contains the context tenant ({@code manual-receipt:<tenant>:<key>}).
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireLock(@Param("lockKey") String lockKey);

}
