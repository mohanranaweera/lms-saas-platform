package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.PlatformOAuthProvider;
import com.lms.integrationmanagement.domain.TenantOAuthApp;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006: every finder runs through {@code
 * TenantAwareRepositoryImpl}'s {@code TenantContext} filter. No method takes
 * a tenant id.
 */
public interface TenantOAuthAppRepository extends TenantAwareRepository<TenantOAuthApp, UUID> {

	default Optional<TenantOAuthApp> findForCurrentTenant(PlatformOAuthProvider provider) {
		return findOne((root, query, cb) -> cb.equal(root.get("provider"), provider));
	}

}
