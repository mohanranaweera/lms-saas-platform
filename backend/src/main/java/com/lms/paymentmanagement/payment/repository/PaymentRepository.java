package com.lms.paymentmanagement.payment.repository;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.paymentmanagement.payment.domain.Payment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006. {@code payment} is append-only in the sense
 * that matters here - no row is ever deleted, only inserted and (once,
 * narrowly) transitioned {@code PENDING -> CONFIRMED|REJECTED} - so, per
 * {@code .claude/rules/backend.md}'s append-only enforcement guidance and
 * mirroring {@code CoursePriceHistoryRepository}'s exact pattern, every
 * delete-shaped method inherited from {@link TenantAwareRepository}/{@code
 * JpaRepository} is overridden below to fail loudly rather than silently
 * existing as an unused-but-callable surface.
 *
 * <p>{@link #findByGatewayReferenceAcrossTenants(String)} is the ONE
 * deliberate exception to this repository's tenant scoping, per ADR-006's
 * {@code findAllAcrossTenants...} naming convention (see {@link
 * TenantAwareRepository}'s javadoc): an explicit {@code @Query}-annotated
 * method (a bare derived-query method name ending in "AcrossTenants" is not
 * itself parseable by Spring Data's method-name query derivation - it tries
 * to resolve "AcrossTenants" as a nested property path and fails - so the
 * query is spelled out explicitly here instead), NOT a {@code default}
 * method built on the inherited {@code Specification}-backed {@code
 * findOne}/{@code findAll} - which would otherwise be tenant-scoped
 * automatically. This is required for the payment-gateway webhook path,
 * which has no subdomain/JWT to resolve tenant
 * identity from; see {@code PaymentConfirmationService}'s javadoc for the
 * full explanation of why this is safe (the found {@code Payment}'s own
 * {@code tenantId} field - set correctly when the row was created during an
 * authenticated request - is what becomes the trusted tenant for the rest of
 * that call, never anything from the webhook payload itself).
 */
public interface PaymentRepository extends TenantAwareRepository<Payment, UUID> {

	@Query("SELECT p FROM Payment p WHERE p.gatewayReference = :gatewayReference")
	Optional<Payment> findByGatewayReferenceAcrossTenants(@Param("gatewayReference") String gatewayReference);

	/**
	 * Locked counterpart to {@link #findByGatewayReferenceAcrossTenants(String)} -
	 * same deliberate cross-tenant exception (see that method's javadoc for
	 * the full webhook-tenant-resolution rationale), but additionally takes a
	 * {@code PESSIMISTIC_WRITE} row lock before {@code
	 * PaymentConfirmationService#confirmByGatewayReference} reads {@code
	 * status}. Without this lock, two genuinely concurrent webhook deliveries
	 * for the same {@code gatewayReference} can both read {@code PENDING},
	 * both pass the "already terminal" guard, and both write their own
	 * {@code PAYMENT_CONFIRMED} ledger_entry row (a duplicate-ledger-entry
	 * race) - mirroring exactly the TOCTOU concern {@link
	 * #findByIdAndTenantIdForUpdate(UUID, UUID)} closes for {@code
	 * RefundService}. Holding this lock for the duration of the
	 * check-then-write serializes concurrent webhook deliveries for the same
	 * reference against each other; the second delivery blocks until the
	 * first commits, then observes the now-terminal status and takes the
	 * existing idempotent no-op path. V20 additionally adds a partial unique
	 * index on {@code ledger_entry} as a defensive backstop in case this lock
	 * is ever bypassed.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.gatewayReference = :gatewayReference")
	Optional<Payment> findByGatewayReferenceAcrossTenantsForUpdate(@Param("gatewayReference") String gatewayReference);

	/**
	 * Explicit {@code @Query} (not the inherited Specification-backed {@code
	 * findOne}/{@code findById}) so a {@code PESSIMISTIC_WRITE} row lock can
	 * be requested - {@code @Lock} has no effect on the default
	 * Specification-based finders. {@code tenantId} is passed explicitly
	 * here (not read implicitly from context the way the base class does)
	 * purely because a custom {@code @Query} method isn't automatically
	 * AND-composed with {@link TenantAwareRepository}'s tenant predicate the
	 * way {@code findOne(Specification)} is - the caller must always pass
	 * {@code tenantContext.getTenantId()}, never a client-supplied value.
	 *
	 * <p>Closes a confirmed TOCTOU race in {@code RefundService#processRefund}:
	 * without a row lock, two concurrent refund requests against the same
	 * payment can each read the same "already refunded" sum, each pass the
	 * refundable-remainder check, and together refund more than the original
	 * payment amount. Holding this lock for the duration of the
	 * check-then-insert serializes concurrent refund attempts against the
	 * same payment row.
	 *
	 * <p>Guarded by {@link #assertTenantIdMatchesContext(UUID)} - see that
	 * method's javadoc - before delegating to {@link
	 * #findByIdAndTenantIdForUpdateUnchecked}, the actual {@code @Lock}/{@code
	 * @Query} method.
	 */
	default Optional<Payment> findByIdAndTenantIdForUpdate(UUID id, UUID tenantId) {
		assertTenantIdMatchesContext(tenantId);
		return findByIdAndTenantIdForUpdateUnchecked(id, tenantId);
	}

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.id = :id AND p.tenantId = :tenantId")
	Optional<Payment> findByIdAndTenantIdForUpdateUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

	/**
	 * Defense-in-depth guard (post-ship review, mirroring {@code
	 * AttendanceRecordRepository#assertTenantIdMatchesContext}'s exact idiom)
	 * for {@link #findByIdAndTenantIdForUpdate}: that method takes {@code
	 * tenantId} as an explicit parameter (see its javadoc) rather than relying
	 * on {@code TenantAwareRepositoryImpl}'s structural {@code Specification}
	 * filtering, so - unlike every other tenant-scoped query in this
	 * repository - there is no compiler/framework safety net if a future
	 * caller passes the wrong value. This asserts the passed {@code tenantId}
	 * agrees with {@link TenantContextHolder}'s own resolved value, throwing
	 * before the query executes on any mismatch. The current call site ({@code
	 * PaymentQueryService#loadPaymentForRefundUpdate}) already passes {@code
	 * TenantContext#getTenantId()}, so this changes no currently-correct
	 * caller's behavior.
	 */
	private static void assertTenantIdMatchesContext(UUID tenantId) {
		UUID currentTenantId = new TenantContextHolder().getTenantId();
		if (!currentTenantId.equals(tenantId)) {
			throw new CrossTenantPersistenceException(
					"Attempted to query payment using a tenantId that does not match the current tenant context");
		}
	}

	default List<Payment> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId) {
		return findAll((root, query, cb) -> cb.equal(root.get("orderId"), orderId),
				Sort.by(Sort.Direction.DESC, "createdAt"));
	}

	default Optional<Payment> findLatestByOrderId(UUID orderId) {
		return findAllByOrderIdOrderByCreatedAtDesc(orderId).stream().findFirst();
	}

	/**
	 * Wave 6 (§3.2/§4) batched read backing {@code PaymentStatusApiImpl
	 * #findOrderPaymentDetails} - every payment attempt (any status) across
	 * ALL of {@code orderIds}, tenant-scoped via the inherited {@code
	 * findAll(Specification)}, mirroring {@code
	 * LedgerEntryRepository#findAllByOrderIdIn}'s exact shape.
	 */
	/**
	 * Wave 6 (§3.3/§4) idempotency-replay lookup - tenant-scoped via the
	 * inherited {@code findOne(Specification)}, mirroring {@code
	 * PaymentRefundRepository#findByOriginalPaymentIdAndIdempotencyKey}'s
	 * exact shape for the sibling payment-initiation replay path.
	 */
	default Optional<Payment> findByOrderIdAndIdempotencyKey(UUID orderId, UUID idempotencyKey) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("orderId"), orderId),
				cb.equal(root.get("idempotencyKey"), idempotencyKey)));
	}

	/**
	 * Wave 6 (§3.3/§4) idempotency race guard - mirrors {@code
	 * StudentOrderRepository#acquireIdempotencyLock}'s exact mechanism/
	 * rationale (see that method's javadoc) for the sibling payment
	 * -initiation replay path: a transaction-scoped Postgres advisory lock,
	 * acquired BEFORE {@code PaymentWriteService#createPendingPayment}'s
	 * idempotency-replay check, serializing two concurrent "Pay Now" clicks
	 * for the same {@code (tenantId, orderId, idempotencyKey)} so the loser
	 * observes the winner's already-committed row instead of racing the
	 * insert.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireIdempotencyLock(@Param("lockKey") String lockKey);

	default List<Payment> findAllByOrderIdIn(List<UUID> orderIds) {
		if (orderIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("orderId").in(orderIds));
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("payment is append-only - no row may ever be deleted");
	}

	@Override
	default void delete(Payment entity) {
		throw new UnsupportedOperationException("payment is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("payment is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends Payment> entities) {
		throw new UnsupportedOperationException("payment is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("payment is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("payment is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<Payment> entities) {
		throw new UnsupportedOperationException("payment is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("payment is append-only - no row may ever be deleted");
	}

}
