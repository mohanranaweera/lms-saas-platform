package com.lms.auditlogmanagement.repository;

import com.lms.auditlogmanagement.domain.AuditLog;
import com.lms.common.persistence.TenantAwareRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006. {@code audit_log} is fully append-only, so
 * (mirroring {@code PaymentRefundRepository}/{@code EnrollmentRepository}'s
 * exact pattern) every delete-shaped method inherited from {@link
 * TenantAwareRepository}/{@code JpaRepository} is overridden below to fail
 * loudly - no repository method anywhere may delete an audit row, including
 * for a platform admin.
 *
 * <p>{@link #findAllAcrossTenantsForPlatformReport} and {@link
 * #findByTenantIdAcrossTenantsForPlatformReport} are the two deliberate
 * exceptions to this repository's tenant scoping, per ADR-006's {@code
 * findAllAcrossTenants...} naming convention (mirroring {@code
 * PaymentRepository#findByGatewayReferenceAcrossTenants}'s exact rationale):
 * explicit {@code @Query}-annotated methods, NOT {@code default} methods
 * built on the inherited {@code Specification}-backed {@code findAll} -
 * which would be tenant-scoped automatically and throw {@code
 * TenantContextNotResolvedException} on a Platform Admin request, since
 * {@code TenantResolutionFilter} never resolves a {@code TenantContext} for
 * {@code /api/v1/platform-admin/**}.
 *
 * <p>These were originally one method, {@code
 * findAllAcrossTenantsForPlatformReport(UUID tenantId, ...)}, whose {@code
 * tenantId} filter was expressed as {@code a.tenant_id = COALESCE(CAST(
 * :tenantId AS uuid), a.tenant_id)} so a single query could serve both the
 * unfiltered platform-wide scan ({@code tenantId == null}) and the
 * single-tenant drill-down ({@code tenantId != null}). That self-referencing
 * predicate is NOT sargable against any index leading with {@code
 * tenant_id} - PostgreSQL cannot push {@code column = COALESCE(param,
 * column)} down as an index condition, since the right-hand side references
 * the column itself, so even the single-tenant drill-down (which should be
 * the cheapest case) degraded to the same platform-wide scan cost as the
 * unfiltered view (confirmed via {@code EXPLAIN} against a real
 * Testcontainers Postgres run). Splitting into two methods lets the
 * drill-down express {@code tenant_id = :tenantId} as a real, sargable
 * equality predicate, letting the planner use {@code
 * idx_audit_log_tenant_occurred_at (tenant_id, occurred_at DESC)} (added in
 * V21) instead of falling back to {@code idx_audit_log_occurred_at_tenant
 * (occurred_at DESC, tenant_id)} (added in V31 specifically for the
 * unfiltered platform-wide case, which {@link
 * #findAllAcrossTenantsForPlatformReport} alone still uses).
 */
public interface AuditLogRepository extends TenantAwareRepository<AuditLog, UUID> {

	/**
	 * Platform-wide, unfiltered-by-tenant audit log read for {@code
	 * PlatformAdminAuditLogQueryService#getPlatformLog}. {@code from}/{@code
	 * to}/{@code action} are each optional filters - {@code null} widens the
	 * search, mirroring {@code AuditLogSpecifications}'s per-field predicate
	 * idiom. Backed by V31's {@code idx_audit_log_occurred_at_tenant
	 * (occurred_at DESC, tenant_id)} index - correct here because this query
	 * has no {@code tenant_id} predicate at all, so an index leading with
	 * {@code occurred_at} is exactly what an unfiltered, time-ordered,
	 * cross-tenant scan needs.
	 *
	 * <p>This is a NATIVE query with explicit PostgreSQL casts on every bind
	 * parameter ({@code CAST(:param AS ...)}), not JPQL. Two JPQL forms were
	 * tried and rejected against a real Testcontainers Postgres run before
	 * landing here:
	 * <ol>
	 * <li>{@code (:param IS NULL OR column = :param)} - fails immediately
	 * with "could not determine data type of parameter", since a bind
	 * parameter whose only appearance is inside an untyped {@code IS NULL}
	 * check gives the driver no type to infer.
	 * <li>{@code column = COALESCE(:param, column)} - fixes the immediate
	 * failure (COALESCE against the typed entity column gives the parameter
	 * a type at parse time), but reappears intermittently once the
	 * PostgreSQL JDBC driver's {@code prepareThreshold} (default 5) is
	 * crossed and the connection switches this statement to a server-side
	 * prepared statement - at that point Postgres re-attempts strict
	 * PREPARE-time type resolution and can fail again for the exact same
	 * underlying reason, non-deterministically depending on how many prior
	 * executions share the connection (confirmed: passed in isolation, then
	 * failed intermittently as part of the full suite).
	 * </ol>
	 * An explicit {@code CAST(:param AS type)} removes the ambiguity
	 * entirely - the parameter's type is spelled out in the SQL text itself,
	 * so Postgres never needs to infer it, regardless of simple vs.
	 * server-side prepared execution. {@code countQuery} is required
	 * alongside a native {@code Page}-returning query so Spring Data can
	 * still page correctly. When a filter parameter is {@code null}, its
	 * comparison degenerates to {@code column = column}/{@code column >=
	 * column}/{@code column <= column}, which is always true for a
	 * {@code NOT NULL} column - semantically identical to "no filter
	 * applied". This same {@code COALESCE} idiom remains correct here
	 * (unlike for {@code tenant_id} below) because {@code from}/{@code
	 * to}/{@code action} are genuinely optional filters with no dedicated
	 * index of their own to protect - the sargability concern that forced
	 * splitting out {@code tenant_id} does not apply to them.
	 */
	@Query(value = "SELECT * FROM audit_log a WHERE "
			+ "a.occurred_at >= COALESCE(CAST(:from AS timestamptz), a.occurred_at) "
			+ "AND a.occurred_at <= COALESCE(CAST(:to AS timestamptz), a.occurred_at) "
			+ "AND a.action = COALESCE(CAST(:action AS varchar), a.action) ORDER BY a.occurred_at DESC",
			countQuery = "SELECT count(*) FROM audit_log a WHERE "
					+ "a.occurred_at >= COALESCE(CAST(:from AS timestamptz), a.occurred_at) "
					+ "AND a.occurred_at <= COALESCE(CAST(:to AS timestamptz), a.occurred_at) "
					+ "AND a.action = COALESCE(CAST(:action AS varchar), a.action)",
			nativeQuery = true)
	Page<AuditLog> findAllAcrossTenantsForPlatformReport(@Param("from") Instant from, @Param("to") Instant to,
			@Param("action") String action, Pageable pageable);

	/**
	 * Single-tenant drill-down audit log read for {@code
	 * PlatformAdminAuditLogQueryService#getTenantDrillDown} - {@code
	 * tenantId} is always non-null here (the caller validates it names a
	 * real tenant, via {@code TenantLookupApi}, before this method is ever
	 * invoked). Named with the {@code AcrossTenants} bypass convention (see
	 * class javadoc) even though it filters to one tenant, since - like
	 * {@link #findAllAcrossTenantsForPlatformReport} - it takes an explicit
	 * {@code tenantId} parameter rather than the trusted, structurally
	 * injected {@code TenantContext}, and is reachable only from the
	 * Platform Admin request path where no {@code TenantContext} is ever
	 * resolved.
	 *
	 * <p>Expresses {@code tenant_id = CAST(:tenantId AS uuid)} as a real,
	 * sargable equality predicate (not the self-referencing {@code
	 * COALESCE(CAST(:tenantId AS uuid), a.tenant_id)} form the unified
	 * method used before this split - see class javadoc for why that form
	 * could not use any {@code tenant_id}-leading index), so this query can
	 * use V21's {@code idx_audit_log_tenant_occurred_at (tenant_id,
	 * occurred_at DESC)} index - the same index every tenant-scoped audit
	 * log read in this codebase already relies on. {@code from}/{@code
	 * to}/{@code action} keep the exact same optional-filter {@code
	 * COALESCE} idiom as {@link #findAllAcrossTenantsForPlatformReport}, for
	 * the same reasons documented there.
	 */
	@Query(value = "SELECT * FROM audit_log a WHERE a.tenant_id = CAST(:tenantId AS uuid) "
			+ "AND a.occurred_at >= COALESCE(CAST(:from AS timestamptz), a.occurred_at) "
			+ "AND a.occurred_at <= COALESCE(CAST(:to AS timestamptz), a.occurred_at) "
			+ "AND a.action = COALESCE(CAST(:action AS varchar), a.action) ORDER BY a.occurred_at DESC",
			countQuery = "SELECT count(*) FROM audit_log a WHERE a.tenant_id = CAST(:tenantId AS uuid) "
					+ "AND a.occurred_at >= COALESCE(CAST(:from AS timestamptz), a.occurred_at) "
					+ "AND a.occurred_at <= COALESCE(CAST(:to AS timestamptz), a.occurred_at) "
					+ "AND a.action = COALESCE(CAST(:action AS varchar), a.action)",
			nativeQuery = true)
	Page<AuditLog> findByTenantIdAcrossTenantsForPlatformReport(@Param("tenantId") UUID tenantId,
			@Param("from") Instant from, @Param("to") Instant to, @Param("action") String action, Pageable pageable);

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void delete(AuditLog entity) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll(Iterable<? extends AuditLog> entities) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllInBatch(Iterable<AuditLog> entities) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("audit_log is append-only - no row may ever be deleted");
	}

}
