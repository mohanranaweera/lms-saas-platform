package com.lms.tenantmanagement.repository;

import com.lms.tenantmanagement.domain.Tenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately a plain {@link JpaRepository}, never a
 * {@code TenantAwareRepository}. {@code Tenant} does not implement
 * {@code TenantOwned} (see that entity's doc comment), so
 * {@code TenantAwareRepositoryFactoryBean} already falls back to standard
 * Spring Data JPA behavior for this repository automatically - there is
 * nothing tenant-scoped to enforce here, since {@code tenant} rows are the
 * platform's root identity records, not owned by any tenant.
 *
 * <p>Intentionally does not expose a
 * {@code findAllPendingTenantsForPlatformApproval}-style bypass method: that
 * belongs to {@code TEN-2}'s approval-queue listing endpoint, which is
 * explicitly deferred (no controller consumes it in this slice) - adding it
 * now would be unused scope creep.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

	Optional<Tenant> findBySubdomain(String subdomain);

	boolean existsBySubdomain(String subdomain);

}
