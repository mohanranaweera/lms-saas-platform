package com.lms.tenantmanagement.repository;

import com.lms.tenantmanagement.domain.Tenant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Platform-level - {@code tenant} is not a tenant-owned table (it IS the
 * tenant identity table), so this extends plain {@link JpaRepository}, not
 * {@code TenantAwareRepository}. Package-private-in-spirit: only
 * {@code TenantService} should use this directly; other modules must go
 * through {@code TenantLookupService}.
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

	Optional<Tenant> findBySubdomain(String subdomain);

}
