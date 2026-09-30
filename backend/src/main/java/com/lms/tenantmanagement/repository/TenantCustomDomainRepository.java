package com.lms.tenantmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.tenantmanagement.domain.CustomDomainStatus;
import com.lms.tenantmanagement.domain.TenantCustomDomain;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * TEN-DOM-01 / ADR-021. The specification-based defaults (and {@code
 * findById}/{@code save}) are tenant-scoped by {@code TenantAwareRepositoryImpl}
 * from the current TenantContext. Races (two claims of one hostname, two
 * primaries) are settled by V63's partial unique indexes.
 *
 * <p>The {@code ...AcrossTenants} methods are the deliberate cross-tenant
 * reads (ADR-021 decision 3, the ADR-011 carve-out pattern), each fed only by
 * server-side input - never a client-supplied tenant id:
 * <ul>
 * <li>{@link #findResolvableByHostnameAcrossTenants} - the request Host maps
 * to its owning tenant; that is how the tenant is found at all.</li>
 * <li>{@link #existsClaimedHostnameAcrossTenants} - a hostname belongs to one
 * tenant across the platform.</li>
 * <li>{@link #findPrimaryActiveForTenantAcrossTenants} and
 * {@link #findHostnamesForTenantAcrossTenants} - link building and cache
 * eviction from a tenant id the server already holds (an outbox row, a
 * payment, a Platform Admin path id).</li>
 * <li>{@link #findIdsByStatusInAcrossTenants} and {@link #findByIdAcrossTenants}
 * - the daily DNS re-check job, which then works inside each row's own tenant.</li>
 * </ul>
 */
public interface TenantCustomDomainRepository extends TenantAwareRepository<TenantCustomDomain, UUID> {

	default List<TenantCustomDomain> findAllNotRemoved() {
		return findAll((root, query, cb) -> cb.notEqual(root.get("status"), CustomDomainStatus.REMOVED),
				Sort.by("createdAt"));
	}

	default long countNotRemoved() {
		return count((root, query, cb) -> cb.notEqual(root.get("status"), CustomDomainStatus.REMOVED));
	}

	default Optional<TenantCustomDomain> findCurrentPrimary() {
		return findOne((root, query, cb) -> cb.isTrue(root.get("primary")));
	}

	@Query("SELECT d FROM TenantCustomDomain d WHERE d.hostname = :hostname"
			+ " AND d.status = com.lms.tenantmanagement.domain.CustomDomainStatus.ACTIVE")
	Optional<TenantCustomDomain> findResolvableByHostnameAcrossTenants(@Param("hostname") String hostname);

	@Query("SELECT COUNT(d) > 0 FROM TenantCustomDomain d WHERE d.hostname = :hostname"
			+ " AND d.status <> com.lms.tenantmanagement.domain.CustomDomainStatus.REMOVED")
	boolean existsClaimedHostnameAcrossTenants(@Param("hostname") String hostname);

	@Query("SELECT d FROM TenantCustomDomain d WHERE d.tenantId = :tenantId AND d.primary = true"
			+ " AND d.status = com.lms.tenantmanagement.domain.CustomDomainStatus.ACTIVE")
	Optional<TenantCustomDomain> findPrimaryActiveForTenantAcrossTenants(@Param("tenantId") UUID tenantId);

	@Query("SELECT d.hostname FROM TenantCustomDomain d WHERE d.tenantId = :tenantId")
	List<String> findHostnamesForTenantAcrossTenants(@Param("tenantId") UUID tenantId);

	@Query("SELECT d.id FROM TenantCustomDomain d WHERE d.status IN :statuses ORDER BY d.lastCheckedAt ASC NULLS FIRST")
	List<UUID> findIdsByStatusInAcrossTenants(@Param("statuses") Collection<CustomDomainStatus> statuses);

	@Query("SELECT d FROM TenantCustomDomain d WHERE d.id = :id")
	Optional<TenantCustomDomain> findByIdAcrossTenants(@Param("id") UUID id);

}
