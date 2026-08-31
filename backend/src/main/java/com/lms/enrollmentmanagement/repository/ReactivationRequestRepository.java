package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006. {@code reactivation_request}'s {@code status}
 * column is a narrow, justified in-place update (mirrors {@code
 * PaymentSlipRepository}'s exact precedent for {@code payment_slip.status})
 * - every delete-shaped method inherited from {@link
 * TenantAwareRepository}/{@code JpaRepository} is overridden below to fail
 * loudly. No row is ever deleted.
 */
public interface ReactivationRequestRepository extends TenantAwareRepository<ReactivationRequest, UUID> {

	/**
	 * Friendly pre-check for "does a SUBMITTED request already exist" -
	 * {@code uq_reactivation_request_tenant_enrollment_live} (V24) is the
	 * real, DB-level guarantee (it covers {@code SUBMITTED} as one of its two
	 * disjuncts, so at most one {@code SUBMITTED} row per (tenant,
	 * enrollment) is still schema-enforced); a genuine race between two
	 * concurrent submissions is still caught by that unique index + the
	 * caller's {@code DataIntegrityViolationException} guard, mirroring
	 * {@code PaymentSlipRepository}'s established idiom.
	 */
	default Optional<ReactivationRequest> findCurrentOpenByEnrollmentId(UUID enrollmentId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("enrollmentId"), enrollmentId),
				cb.equal(root.get("status"), ReactivationRequestStatus.SUBMITTED)));
	}

	/**
	 * "Live" request for an enrollment - {@code SUBMITTED}, OR {@code
	 * APPROVED} but not yet linked to an order ({@code new_order_id IS
	 * NULL}) - backs {@code ReactivationRequestService#submit}'s "at most one
	 * reactivation attempt that could still result in a future order" guard
	 * (bug fix, MVP-012 review; see that method's javadoc for the full rule
	 * rationale). Deliberately does NOT match an {@code APPROVED} request
	 * that is ALREADY linked to an order ({@code new_order_id IS NOT NULL}):
	 * once fulfilled, {@code OrderService}'s own gate (at most one order can
	 * ever be linked to a given still-current, still-unreactivated
	 * enrollment - see {@link #findApprovedUnfulfilledByEnrollmentIdForUpdate})
	 * already keeps "at most one order in flight for this enrollment" intact,
	 * so excluding a fulfilled request here lets a student who reactivates
	 * and later expires again submit a brand-new request without being
	 * blocked by their own, already-fulfilled reactivation history.
	 */
	default Optional<ReactivationRequest> findLiveByEnrollmentId(UUID enrollmentId) {
		Specification<ReactivationRequest> spec = (root, query, cb) -> cb.and(
				cb.equal(root.get("enrollmentId"), enrollmentId),
				cb.or(cb.equal(root.get("status"), ReactivationRequestStatus.SUBMITTED),
						cb.and(cb.equal(root.get("status"), ReactivationRequestStatus.APPROVED),
								cb.isNull(root.get("newOrderId")))));
		return findOne(spec);
	}

	/**
	 * {@code APPROVED} AND not-yet-linked-to-an-order ({@code new_order_id IS
	 * NULL}) - what {@code EnrollmentAccessApi#hasApprovedUnfulfilledReactivationRequest}
	 * needs BEFORE order creation, as a plain, UNLOCKED read (this method is
	 * only ever used for the "should I even attempt this" pre-check, never
	 * immediately before a write - see {@link
	 * #findApprovedUnfulfilledByEnrollmentIdForUpdate} for the locked
	 * counterpart used at actual link-write time). Picks the
	 * most-recently-reviewed match if more than one theoretically exists -
	 * in every realistic scenario there is at most one, since {@link
	 * #findLiveByEnrollmentId(UUID)}'s submission-time guard (bug fix,
	 * MVP-012 review) now keeps that true going forward.
	 */
	default Optional<ReactivationRequest> findApprovedUnfulfilledByEnrollmentId(UUID enrollmentId) {
		Specification<ReactivationRequest> spec = (root, query, cb) -> cb.and(
				cb.equal(root.get("enrollmentId"), enrollmentId),
				cb.equal(root.get("status"), ReactivationRequestStatus.APPROVED),
				cb.isNull(root.get("newOrderId")));
		Pageable mostRecentFirst = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "reviewedAt"));
		return findAll(spec, mostRecentFirst).stream().findFirst();
	}

	/**
	 * Locked counterpart to {@link #findApprovedUnfulfilledByEnrollmentId(UUID)}
	 * (bug fix, MVP-012 review) - used by {@code
	 * ReactivationLinkingApiImpl#linkApprovedRequestToNewOrder} to close the
	 * unlocked check-then-write race between two concurrent {@code
	 * OrderService#createOrder} calls for the same approved-unfulfilled
	 * request: without a lock, both could read {@code newOrderId == null},
	 * both create a separate order, and race to write {@code newOrderId} -
	 * {@code ReactivationRequest} has no {@code @Version} column, so the
	 * second writer's blind {@code UPDATE} would silently overwrite the
	 * first's link with no error to either caller. {@code PESSIMISTIC_WRITE}
	 * makes the second concurrent caller BLOCK until the first's transaction
	 * commits (fixing {@code newOrderId} in place), after which this query's
	 * own {@code newOrderId IS NULL} predicate correctly finds nothing for
	 * the second caller - {@code Optional.empty()} - so it throws {@link
	 * IllegalStateException}, which {@code OrderService#createOrder} maps to
	 * a clean {@code 409}, instead of silently losing its link.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM ReactivationRequest r WHERE r.enrollmentId = :enrollmentId AND r.tenantId = :tenantId "
			+ "AND r.status = :status AND r.newOrderId IS NULL")
	Optional<ReactivationRequest> findApprovedUnfulfilledByEnrollmentIdForUpdate(
			@Param("enrollmentId") UUID enrollmentId, @Param("tenantId") UUID tenantId,
			@Param("status") ReactivationRequestStatus status);

	/**
	 * The exact, unambiguous match {@code EnrollmentActivationService}'s
	 * reactivation methods need at confirmation time (bug fix, MVP-012
	 * review, defense-in-depth cross-check): the {@code APPROVED}
	 * reactivation request for this enrollment whose {@code newOrderId} is
	 * EXACTLY the order that owns the confirming payment/slip - never "the
	 * most recently reviewed approved+linked request for this enrollment"
	 * (which could silently resolve to the WRONG request if more than one
	 * {@code APPROVED}+linked request ever exists for the same {@code
	 * enrollmentId}). Returns empty - never guesses - if no request's {@code
	 * newOrderId} equals {@code orderId}, which the caller treats as a
	 * refusal to reactivate.
	 */
	default Optional<ReactivationRequest> findApprovedByEnrollmentIdAndNewOrderId(UUID enrollmentId, UUID orderId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("enrollmentId"), enrollmentId),
				cb.equal(root.get("status"), ReactivationRequestStatus.APPROVED),
				cb.equal(root.get("newOrderId"), orderId)));
	}

	/** The owning student's own request history - backs {@code GET /api/v1/reactivation-requests/my}. */
	default Page<ReactivationRequest> findAllByRequestedBy(UUID requestedBy, Pageable pageable) {
		return findAll((root, query, cb) -> cb.equal(root.get("requestedBy"), requestedBy), pageable);
	}

	/**
	 * Staff review-queue read: when {@code status} is {@code null}, returns
	 * every {@code SUBMITTED} request (the actual pending-review queue);
	 * when supplied, filters to that exact status only - mirrors {@code
	 * PaymentSlipRepository#findReviewQueue}'s exact shape.
	 */
	default Page<ReactivationRequest> findReviewQueue(ReactivationRequestStatus status, Pageable pageable) {
		Specification<ReactivationRequest> spec = (status != null)
				? (root, query, cb) -> cb.equal(root.get("status"), status)
				: (root, query, cb) -> cb.equal(root.get("status"), ReactivationRequestStatus.SUBMITTED);
		return findAll(spec, pageable);
	}

	/**
	 * Locked counterpart to the inherited tenant-scoped {@code findById},
	 * mirroring {@code PaymentSlipRepository#findByIdAndTenantIdForUpdate}'s
	 * exact rationale - without this lock, two concurrent approve/reject
	 * requests for the same row could both read {@code SUBMITTED} and both
	 * proceed. {@code tenantId} is passed explicitly since a custom {@code
	 * @Query} method isn't automatically composed with {@link
	 * TenantAwareRepository}'s tenant predicate.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM ReactivationRequest r WHERE r.id = :id AND r.tenantId = :tenantId")
	Optional<ReactivationRequest> findByIdAndTenantIdForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("reactivation_request is history - no row may ever be deleted");
	}

	@Override
	default void delete(ReactivationRequest entity) {
		throw new UnsupportedOperationException("reactivation_request is history - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("reactivation_request is history - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends ReactivationRequest> entities) {
		throw new UnsupportedOperationException("reactivation_request is history - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("reactivation_request is history - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("reactivation_request is history - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<ReactivationRequest> entities) {
		throw new UnsupportedOperationException("reactivation_request is history - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("reactivation_request is history - no row may ever be deleted");
	}

}
