package com.lms.paymentmanagement.slip.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.paymentmanagement.slip.domain.PaymentSlipFlag;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. {@code payment_slip_flag} is fully append-only
 * (mirroring {@code PaymentRefundRepository}'s exact pattern) - every
 * delete-shaped method inherited from {@link TenantAwareRepository}/{@code
 * JpaRepository} is overridden below to fail loudly. No update method is
 * exposed anywhere either - {@code SlipDuplicateCheckService} only ever
 * calls {@code save} to insert a brand new flag row, never to mutate one.
 */
public interface PaymentSlipFlagRepository extends TenantAwareRepository<PaymentSlipFlag, UUID> {

	/** Full flag history for one slip (never latest-only) - backs slip detail/review-queue flag display. */
	default List<PaymentSlipFlag> findAllBySlipId(UUID slipId) {
		return findAll((root, query, cb) -> cb.equal(root.get("slipId"), slipId));
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("payment_slip_flag is append-only - no row may ever be deleted");
	}

	@Override
	default void delete(PaymentSlipFlag entity) {
		throw new UnsupportedOperationException("payment_slip_flag is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("payment_slip_flag is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends PaymentSlipFlag> entities) {
		throw new UnsupportedOperationException("payment_slip_flag is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("payment_slip_flag is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("payment_slip_flag is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<PaymentSlipFlag> entities) {
		throw new UnsupportedOperationException("payment_slip_flag is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("payment_slip_flag is append-only - no row may ever be deleted");
	}

}
