package com.lms.ledgersettlementmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.ledgersettlementmanagement.domain.LedgerEntry;
import com.lms.ledgersettlementmanagement.domain.LedgerEntryType;
import java.time.Instant;
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
 * <p>{@link #findAllAcrossTenantsForPlatformReport}, {@link
 * #findAllAcrossTenantsForPlatformReportUnpaged}, {@link
 * #findByTenantIdAcrossTenantsForPlatformReport}, and {@link
 * #findByTenantIdAcrossTenantsForPlatformReportUnpaged} are the deliberate
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
	 * Platform-wide, unfiltered-by-tenant ledger read, UNPAGED - the
	 * platform-report sibling of {@link #findAllForDashboardUnpaged()}. Used
	 * only when {@code PlatformAdminLedgerQueryService#getPlatformDashboard}'s
	 * {@code status}/{@code method} filters are supplied: those two fields
	 * are computed/cross-module-derived (never a {@code ledger_entry} column),
	 * so - exactly as {@link #findAllForDashboardUnpaged()}'s javadoc
	 * explains for the tenant-scoped dashboard - they cannot be pushed down
	 * into this query's {@code WHERE} clause. The caller enriches+filters
	 * this full result set in application code, then paginates the FILTERED
	 * list itself, so {@code totalElements}/{@code totalPages} always
	 * reflect the actual filtered result set rather than the unfiltered DB
	 * count. Acceptable at current data volumes per plan §10 judgment call
	 * 3, same as the tenant-scoped equivalent.
	 */
	@Query("SELECT le FROM LedgerEntry le ORDER BY le.createdAt DESC")
	List<LedgerEntry> findAllAcrossTenantsForPlatformReportUnpaged();

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
	 * Single-tenant drill-down ledger read, UNPAGED - the drill-down sibling
	 * of {@link #findAllAcrossTenantsForPlatformReportUnpaged()}, used for
	 * the same {@code status}/{@code method}-filtered reason on {@code
	 * PlatformAdminLedgerQueryService#getTenantDrillDown}. See that method's
	 * javadoc.
	 */
	@Query("SELECT le FROM LedgerEntry le WHERE le.tenantId = :tenantId ORDER BY le.createdAt DESC")
	List<LedgerEntry> findByTenantIdAcrossTenantsForPlatformReportUnpaged(@Param("tenantId") UUID tenantId);

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

	/**
	 * Wave 6 (§4) - every entry in the caller's own tenant, UNPAGED, sorted
	 * newest-first. Used only when {@code GET /api/v1/ledger/dashboard}'s
	 * {@code status}/{@code method} filters are supplied: those two fields
	 * are computed/cross-module-derived (never a {@code ledger_entry}
	 * column), so they cannot be pushed down into this query's {@code
	 * WHERE} clause - the caller enriches+filters this full result set in
	 * application code, then paginates the FILTERED list itself, so a
	 * filtered page is never silently short/wrong (a DB-level paginate
	 * -then-filter would return partial-looking pages). Acceptable at
	 * current data volumes per plan §10 judgment call 3.
	 */
	default List<LedgerEntry> findAllForDashboardUnpaged() {
		return findAll((root, query, cb) -> cb.conjunction(), Sort.by(Sort.Direction.DESC, "createdAt"));
	}

	/**
	 * Wave 7 (§4) - every entry in the caller's own tenant whose {@code
	 * createdAt} falls in {@code [fromInclusive, toExclusive)}, oldest first.
	 * Specification-based (never a bare {@code @Query}), so the ADR-006
	 * tenant predicate is applied structurally by {@code
	 * TenantAwareRepositoryImpl}. Backs finance reporting and teacher
	 * settlement calculation - both strictly ledger-derived.
	 */
	default List<LedgerEntry> findAllCreatedBetween(Instant fromInclusive, Instant toExclusive) {
		return findAll((root, query, cb) -> cb.and(cb.greaterThanOrEqualTo(root.get("createdAt"), fromInclusive),
				cb.lessThan(root.get("createdAt"), toExclusive)), Sort.by(Sort.Direction.ASC, "createdAt"));
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
