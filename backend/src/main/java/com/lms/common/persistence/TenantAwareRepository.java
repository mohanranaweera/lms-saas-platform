package com.lms.common.persistence;

import java.io.Serializable;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * Structural tenant-filtering mechanism decided in ADR-006: every
 * tenant-owned entity's repository extends this instead of
 * {@link JpaRepository} directly. Standard finders (findById, findAll,
 * count, existsById, ...) are scoped to the tenant resolved from
 * {@link com.lms.common.tenant.TenantContext} - see
 * {@link TenantAwareRepositoryImpl} for the enforcement.
 *
 * A method that must legitimately read across tenants (platform-admin
 * reporting, cross-tenant support tooling) must NOT be added here - it
 * belongs on a distinctly named method/interface (e.g.
 * {@code findAllAcrossTenantsForPlatformReport}) so the bypass stays visible
 * in review, per ADR-006.
 */
@NoRepositoryBean
public interface TenantAwareRepository<T extends TenantOwned, ID extends Serializable>
		extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {

	/**
	 * The values of one attribute of the current tenant's rows matching {@code
	 * spec} - a narrow projection (only that column is selected) with the same
	 * structural tenant predicate as every other finder here. For bounded
	 * report lookups that need ids only, not whole entities.
	 */
	<R> List<R> findAttributeValues(Specification<T> spec, String attribute, Class<R> type);

}
