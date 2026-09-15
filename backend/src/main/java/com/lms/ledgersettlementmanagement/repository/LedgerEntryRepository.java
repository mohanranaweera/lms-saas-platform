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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006. {@code ledger_entry} is fully append-only, so
 * (mirroring {@code CoursePriceHistoryRepository}'s exact pattern) every
 * delete-shaped method inherited from {@link TenantAwareRepository}/{@code
 * JpaRepository} is overridden below to fail loudly - PAY-3's own
 * acceptance criterion ("no repository method exposes delete/deleteById").
 *
 * <p>{@link #findAllAcrossTenantsForPlatformReport} and {@link
 * #findByTenantIdAcrossTenantsForPlatformReport} are the two deliberate
 * exceptions to this repository's tenant scoping, per ADR-006's {@code
 * findAllAcrossTenants...} naming convention (mirroring {@code
 * PaymentRepository#findByGatewayReferenceAcrossTenants}'s exact rationale):
 * explicit {@code @Query}-annotated methods, NOT {@code default} methods
 * built on the inherited {@code Specification}-backed {@code findAll} -
 * which would be tenant-scoped automatically and throw {@code
 * TenantContextNotResolvedException} on a Platform Admin request.
 *
 * <p>These were originally one method taking a nullable {@code tenantId},
 * filtered with {@code (:tenantId IS NULL OR le.tenantId = :tenantId)}. That
 * form is correct and (unlike {@code AuditLogRepository}'s equivalent
 * native-query {@code COALESCE} form) genuinely sargable for a SIMPLE
 * (client-side) prepared statement - PostgreSQL's planner can prove
 * {@code le.tenantId = :tenantId} once {@code :tenantId} is bound to a
 * concrete, non-null value at plan time. The residual risk this split
 * closes is a prepared-statement plan-cache one, not a per-call sargability
 * one: PgJDBC switches a repeatedly-executed statement to a cached
 * "generic" server-side plan once its {@code prepareThreshold} (default 5)
 * is crossed, and a generic plan is chosen once, up front, without knowing
 * whether a given execution's {@code :tenantId} will be {@code null} or
 * not - it could settle on a plan good for one case and bad for the other.
 * Splitting into two methods, each with its own fixed (non-parameterized)
 * WHERE-clause shape, removes that ambiguity entirely: there is no longer
 * a single statement whose "optimal plan" depends on a value only known at
 * execution time. This mirrors the same class of fix applied to {@code
 * AuditLogRepository}'s equivalent method for the same underlying reason
 * (see that class's javadoc), keeping both platform-report repositories
 * consistent.
 */
public interface LedgerEntryRepository extends TenantAwareRepository<LedgerEntry, UUID> {

	/**
	 * Platform-wide, unfiltered-by-tenant ledger read for {@code
	 * PlatformAdminLedgerQueryService#getPlatformDashboard}. Ordered by
	 * {@code createdAt DESC}, backed by V31's {@code
	 * idx_ledger_entry_created_at_tenant (created_at DESC, tenant_id)}
	 * index - correct here because this query carries no {@code tenant_id}
	 * predicate at all.
	 */
	@Query("SELECT le FROM LedgerEntry le ORDER BY le.createdAt DESC")
	Page<LedgerEntry> findAllAcrossTenantsForPlatformReport(Pageable pageable);

	/**
	 * Single-tenant drill-down ledger read for {@code
	 * PlatformAdminLedgerQueryService#getTenantDrillDown} - {@code tenantId}
	 * is always non-null here (the caller validates it names a real tenant,
	 * via {@code TenantLookupApi}, before this method is ever invoked).
	 * Named with the {@code AcrossTenants} bypass convention (see class
	 * javadoc) even though it filters to one tenant, since - like {@link
	 * #findAllAcrossTenantsForPlatformReport} - it takes an explicit {@code
	 * tenantId} parameter rather than the trusted, structurally injected
	 * {@code TenantContext}, and is reachable only from the Platform Admin
	 * request path. A fixed, unconditional {@code le.tenantId = :tenantId}
	 * predicate lets this query use the same tenant-leading index every
	 * tenant-scoped ledger read in this codebase already relies on,
	 * regardless of prepared-statement plan caching (see class javadoc).
	 */
	@Query("SELECT le FROM LedgerEntry le WHERE le.tenantId = :tenantId ORDER BY le.createdAt DESC")
	Page<LedgerEntry> findByTenantIdAcrossTenantsForPlatformReport(@Param("tenantId") UUID tenantId, Pageable pageable);

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
