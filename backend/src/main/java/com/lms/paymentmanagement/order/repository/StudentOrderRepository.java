package com.lms.paymentmanagement.order.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006. Custom finders are {@code default} methods built
 * on the {@link org.springframework.data.jpa.domain.Specification}-backed
 * {@code findAll} inherited from {@link TenantAwareRepository} - never a
 * Spring Data derived-query method, which would bypass {@code
 * TenantAwareRepositoryImpl}'s tenant-scoping override (mirroring {@code
 * coursemanagement.course.repository.CourseRepository}'s established
 * pattern exactly).
 *
 * <p>{@code student_order} rows must outlive their own lifecycle for the same
 * "never delete financial history" reason {@code payment}/{@code
 * ledger_entry}/{@code payment_refund}/{@code enrollment} do (an order is the
 * origin of every payment/ledger row that references it via composite FK) -
 * so, mirroring those four repositories' exact pattern, every delete-shaped
 * method inherited from {@link TenantAwareRepository}/{@code JpaRepository}
 * is overridden below to fail loudly rather than silently existing as an
 * unused-but-callable surface.
 */
public interface StudentOrderRepository extends TenantAwareRepository<StudentOrder, UUID> {

	default List<StudentOrder> findAllByStudentId(UUID studentId) {
		return findAll((root, query, cb) -> cb.equal(root.get("studentId"), studentId));
	}

	/** Wave 6 (§4) course-payment-summary read pattern - tenant-scoped via the inherited {@code findAll(Specification)}. */
	default List<StudentOrder> findAllByCourseId(UUID courseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId));
	}

	/**
	 * Wave 6 (§3.3/§4) idempotency-replay lookup - tenant-scoped via the
	 * inherited {@code findOne(Specification)}, mirroring {@code
	 * PaymentRefundRepository#findByOriginalPaymentIdAndIdempotencyKey}'s
	 * exact shape for the sibling order-creation replay path.
	 */
	default Optional<StudentOrder> findByStudentIdAndIdempotencyKey(UUID studentId, UUID idempotencyKey) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("idempotencyKey"), idempotencyKey)));
	}

	/**
	 * Wave 6 (§3.3/§4) idempotency race guard - acquires a transaction-scoped
	 * Postgres advisory lock ({@code pg_advisory_xact_lock}, automatically
	 * released at this transaction's commit/rollback, never needing an
	 * explicit unlock call) keyed by the caller-supplied dedup string
	 * (conventionally {@code tenantId:studentId:idempotencyKey}), BEFORE the
	 * check-then-insert idempotency replay logic in {@code
	 * OrderService#createOrder} runs.
	 *
	 * <p>This is NOT this codebase's usual "lock an existing parent row"
	 * idiom ({@code RefundService}/{@code ReactivationLinkingApiImpl}) -
	 * order creation has no pre-existing row to lock ahead of the very
	 * insert being deduplicated. It is also deliberately NOT a bare {@code
	 * try/catch(DataIntegrityViolationException)} around the insert: {@code
	 * ReactivationTransactionService}'s own javadoc documents, from a real
	 * prior investigation, that {@code Propagation.NESTED} (savepoints) is
	 * confirmed unworkable with this codebase's {@code JpaTransactionManager}
	 * /Hibernate stack, and that Postgres aborts the WHOLE ambient
	 * transaction on a genuine constraint violation - a plain catch cannot
	 * "un-abort" it, so a subsequent re-query in the SAME transaction would
	 * itself fail. Serializing on this advisory lock BEFORE either
	 * concurrent caller's check-then-insert runs means the second caller
	 * blocks until the first's transaction commits (releasing the lock),
	 * then its own idempotency-replay SELECT correctly observes the first
	 * caller's already-committed row - so the insert-and-collide path is
	 * never reached at all by the loser, never merely caught-and-hopefully-
	 * recovered.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireIdempotencyLock(@Param("lockKey") String lockKey);

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void delete(StudentOrder entity) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends StudentOrder> entities) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<StudentOrder> entities) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("student_order is append-only - no row may ever be deleted");
	}

}
