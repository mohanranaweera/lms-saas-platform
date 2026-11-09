package com.lms.tutemanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.tutemanagement.domain.FulfillmentMethod;
import com.lms.tutemanagement.domain.FulfillmentSource;
import com.lms.tutemanagement.domain.FulfillmentStatus;
import com.lms.tutemanagement.domain.TuteFulfillment;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006 ({@code .claude/rules/tenancy.md}); never used outside {@code
 * com.lms.tutemanagement}. Inserts go only through {@link TuteFulfillmentWrites#insertIfAbsent}
 * (idempotent); there is no delete method - fulfillment history is kept (a tute with history is
 * archived, never deleted).
 */
public interface TuteFulfillmentRepository
		extends TenantAwareRepository<TuteFulfillment, UUID>, TuteFulfillmentWrites {

	Set<FulfillmentStatus> ALLOCATED = Set.of(FulfillmentStatus.PENDING, FulfillmentStatus.DISPATCHED);

	Sort CREATED_ORDER = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

	/** Copies held against the tute's stock (PENDING + DISPATCHED). */
	default long countAllocated(UUID tuteId) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("tuteId"), tuteId),
				root.get("status").in(ALLOCATED)));
	}

	default boolean existsByTuteId(UUID tuteId) {
		return exists((root, query, cb) -> cb.equal(root.get("tuteId"), tuteId));
	}

	default List<TuteFulfillment> findAllByTuteId(UUID tuteId) {
		return findAll((root, query, cb) -> cb.equal(root.get("tuteId"), tuteId), CREATED_ORDER);
	}

	/** Students holding a non-cancelled copy of the tute. */
	default List<UUID> findActiveStudentIds(UUID tuteId) {
		return findAttributeValues((root, query, cb) -> cb.and(cb.equal(root.get("tuteId"), tuteId),
				cb.notEqual(root.get("status"), FulfillmentStatus.CANCELLED)), "studentId", UUID.class);
	}

	/**
	 * Any row (also cancelled) of the tute sourced from the payment: a payment never re-assigns a
	 * copy staff already unrolled.
	 */
	default boolean existsByTuteIdAndSourcePaymentId(UUID tuteId, UUID paymentId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("tuteId"), tuteId),
				cb.equal(root.get("sourcePaymentId"), paymentId)));
	}

	/** Payment-sourced, non-cancelled rows of one payment (refund / chargeback hold). */
	default List<TuteFulfillment> findActiveBySourcePaymentId(UUID paymentId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("sourcePaymentId"), paymentId),
				cb.equal(root.get("source"), FulfillmentSource.PAYMENT),
				cb.notEqual(root.get("status"), FulfillmentStatus.CANCELLED)));
	}

	/** Payment-sourced, non-cancelled rows of one tute whose reversal flag is not yet set (sync). */
	default List<TuteFulfillment> findUnflaggedPaymentRowsOfTute(UUID tuteId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("tuteId"), tuteId),
				cb.equal(root.get("source"), FulfillmentSource.PAYMENT),
				cb.notEqual(root.get("status"), FulfillmentStatus.CANCELLED),
				cb.isNull(root.get("paymentReversedAt"))));
	}

	/**
	 * The Tute Dispatch working set: PENDING POSTAL rows not on hold, optionally limited to
	 * {@code courseIds} ({@code null} = every course of the tenant).
	 */
	default List<TuteFulfillment> findDispatchable(Collection<UUID> courseIds) {
		return findAll((root, query, cb) -> cb.and(dispatchable(root, cb, courseIds)), CREATED_ORDER);
	}

	default long countDispatchable(Collection<UUID> courseIds) {
		return count((root, query, cb) -> cb.and(dispatchable(root, cb, courseIds)));
	}

	private static Predicate[] dispatchable(jakarta.persistence.criteria.Root<TuteFulfillment> root,
			jakarta.persistence.criteria.CriteriaBuilder cb, Collection<UUID> courseIds) {
		List<Predicate> predicates = new ArrayList<>();
		predicates.add(cb.equal(root.get("status"), FulfillmentStatus.PENDING));
		predicates.add(cb.equal(root.get("method"), FulfillmentMethod.POSTAL));
		predicates.add(cb.or(cb.isNull(root.get("paymentReversedAt")), cb.isNotNull(root.get("holdReleasedAt"))));
		if (courseIds != null) {
			predicates.add(courseIds.isEmpty() ? cb.disjunction() : root.get("courseId").in(courseIds));
		}
		return predicates.toArray(Predicate[]::new);
	}

}
