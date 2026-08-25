package com.lms.paymentmanagement.slip.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.paymentmanagement.slip.domain.PaymentSlip;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006. {@code payment_slip} is financial evidence that
 * must never be deleted (root {@code CLAUDE.md}'s "never delete financial
 * history" rule) even though its {@code status} column is a narrow,
 * justified in-place update (mirroring {@code Payment}'s own precedent) - so
 * (mirroring {@code PaymentRepository}'s exact pattern) every delete-shaped
 * method inherited from {@link TenantAwareRepository}/{@code JpaRepository}
 * is overridden below to fail loudly. No generic bulk-update method is added
 * here - only the narrow finders {@code SlipUploadService}/{@code
 * SlipDuplicateCheckService}/{@code SlipReviewService} actually need, plus
 * the inherited {@code save} for the one insert and the one justified
 * status-transition update.
 */
public interface PaymentSlipRepository extends TenantAwareRepository<PaymentSlip, UUID> {

	/** Duplicate-reference-number candidate lookup (SLIP-2) - tenant-scoped via the inherited {@code findAll(Specification)}. */
	default List<PaymentSlip> findAllByReferenceNumber(String referenceNumber) {
		return findAll((root, query, cb) -> cb.equal(root.get("referenceNumber"), referenceNumber));
	}

	/** Duplicate-image-hash candidate lookup (SLIP-2) - tenant-scoped via the inherited {@code findAll(Specification)}. */
	default List<PaymentSlip> findAllByImageHash(String imageHash) {
		return findAll((root, query, cb) -> cb.equal(root.get("imageHash"), imageHash));
	}

	/**
	 * Pre-check backing {@code SlipUploadService}'s friendly-message guard for
	 * the {@code uq_payment_slip_tenant_order_active} unique index: {@code
	 * true} when {@code orderId} already carries a {@code SUBMITTED}/{@code
	 * UNDER_REVIEW} ("active") slip. Tenant-scoped via the inherited {@code
	 * findAll(Specification)} - never a caller-supplied tenant id. This is a
	 * best-effort pre-check only (a genuine race between two concurrent
	 * uploads for the same order is still caught by the DB unique index
	 * itself); it exists purely so the common, non-racy case surfaces a
	 * specific {@code ConflictException} message instead of a generic {@code
	 * DataIntegrityViolationException}-mapped 409.
	 */
	default boolean existsActiveSlipForOrder(UUID orderId) {
		return !findAll((root, query, cb) -> cb.and(cb.equal(root.get("orderId"), orderId),
				root.get("status").in(PaymentSlipStatus.SUBMITTED, PaymentSlipStatus.UNDER_REVIEW))).isEmpty();
	}

	/**
	 * Review-queue read (SLIP-3): when {@code status} is {@code null},
	 * returns every {@code SUBMITTED}/{@code UNDER_REVIEW} slip (the actual
	 * "pending review" queue); when supplied, filters to that exact status
	 * only (so a reviewer can also look up e.g. already-{@code APPROVED}
	 * slips). Tenant-scoped via the inherited {@code findAll(Specification,
	 * Pageable)}.
	 */
	default Page<PaymentSlip> findReviewQueue(PaymentSlipStatus status, Pageable pageable) {
		Specification<PaymentSlip> spec = (status != null)
				? (root, query, cb) -> cb.equal(root.get("status"), status)
				: (root, query, cb) -> root.get("status")
					.in(PaymentSlipStatus.SUBMITTED, PaymentSlipStatus.UNDER_REVIEW);
		return findAll(spec, pageable);
	}

	/**
	 * Locked counterpart to the inherited tenant-scoped {@code findById},
	 * mirroring {@code PaymentRepository#findByIdAndTenantIdForUpdate}'s
	 * exact rationale: without this lock, two concurrent approve/reject
	 * requests for the same slip could both read {@code UNDER_REVIEW}, both
	 * pass the "not yet terminal" guard, and both call {@code
	 * EnrollmentActivationApi}/{@code AuditLogApi} a second time. {@code
	 * tenantId} is passed explicitly (not read implicitly from context)
	 * because a custom {@code @Query} method isn't automatically AND-composed
	 * with {@link TenantAwareRepository}'s tenant predicate - the caller must
	 * always pass {@code tenantContext.getTenantId()}, never a
	 * client-supplied value.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM PaymentSlip s WHERE s.id = :id AND s.tenantId = :tenantId")
	Optional<PaymentSlip> findByIdAndTenantIdForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

	/**
	 * A scalar/projection status-only peek, deliberately NOT an entity load -
	 * used by {@code SlipReviewService#approve}'s unlocked idempotent-replay
	 * check. This must stay scalar rather than {@code Optional<PaymentSlip>}:
	 * loading the {@code PaymentSlip} ENTITY here first (even unlocked) would
	 * populate Hibernate's first-level cache/identity map for this id within
	 * the current transaction, and the subsequent {@link
	 * #findByIdAndTenantIdForUpdate} call would then return that
	 * already-managed (and now stale) instance instead of the fresh
	 * post-lock-acquisition row it just blocked to read - silently
	 * defeating the {@code PESSIMISTIC_WRITE} lock's whole guarantee for two
	 * genuinely concurrent callers. A plain scalar projection never enters
	 * the identity map, so it cannot shadow the locked read that follows it.
	 */
	@Query("SELECT s.status FROM PaymentSlip s WHERE s.id = :id AND s.tenantId = :tenantId")
	Optional<PaymentSlipStatus> findStatusByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("payment_slip is financial history - no row may ever be deleted");
	}

	@Override
	default void delete(PaymentSlip entity) {
		throw new UnsupportedOperationException("payment_slip is financial history - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("payment_slip is financial history - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends PaymentSlip> entities) {
		throw new UnsupportedOperationException("payment_slip is financial history - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("payment_slip is financial history - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("payment_slip is financial history - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<PaymentSlip> entities) {
		throw new UnsupportedOperationException("payment_slip is financial history - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("payment_slip is financial history - no row may ever be deleted");
	}

}
