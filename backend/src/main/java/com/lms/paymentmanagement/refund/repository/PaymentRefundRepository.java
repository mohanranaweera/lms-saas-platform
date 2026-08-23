package com.lms.paymentmanagement.refund.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.paymentmanagement.refund.domain.PaymentRefund;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. {@code payment_refund} is fully append-only, so
 * (mirroring {@code CoursePriceHistoryRepository}'s exact pattern) every
 * delete-shaped method inherited from {@link TenantAwareRepository}/{@code
 * JpaRepository} is overridden below to fail loudly.
 */
public interface PaymentRefundRepository extends TenantAwareRepository<PaymentRefund, UUID> {

	/**
	 * Backs {@code RefundService}'s "refund amount cannot exceed the
	 * original payment amount minus the sum of all prior refunds" guard - a
	 * cross-row aggregation the DB {@code CHECK} constraint cannot itself
	 * express (V19 header comment), so it is computed here from the full set
	 * of prior refunds against this payment.
	 */
	default List<PaymentRefund> findAllByOriginalPaymentId(UUID originalPaymentId) {
		return findAll((root, query, cb) -> cb.equal(root.get("originalPaymentId"), originalPaymentId));
	}

	/**
	 * Backs {@code RefundService#processRefund}'s resubmission-idempotency
	 * check (V20) - tenant-scoped via the inherited {@code
	 * Specification}-backed {@code findOne}, so a caller-supplied {@code
	 * idempotencyKey} can never resolve to another tenant's refund.
	 */
	default Optional<PaymentRefund> findByOriginalPaymentIdAndIdempotencyKey(UUID originalPaymentId,
			UUID idempotencyKey) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("originalPaymentId"), originalPaymentId),
				cb.equal(root.get("idempotencyKey"), idempotencyKey)));
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("payment_refund is append-only - no row may ever be deleted");
	}

	@Override
	default void delete(PaymentRefund entity) {
		throw new UnsupportedOperationException("payment_refund is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("payment_refund is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends PaymentRefund> entities) {
		throw new UnsupportedOperationException("payment_refund is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("payment_refund is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("payment_refund is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<PaymentRefund> entities) {
		throw new UnsupportedOperationException("payment_refund is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("payment_refund is append-only - no row may ever be deleted");
	}

}
