package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.videoaccessmanagement.domain.TenantVideoConfiguration;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped per ADR-006 - the structural filter limits every read to the current tenant's single row. */
public interface TenantVideoConfigurationRepository extends TenantAwareRepository<TenantVideoConfiguration, UUID> {

	/** The current tenant's row, if it ever saved its own settings. */
	default Optional<TenantVideoConfiguration> findForCurrentTenant() {
		return findOne((root, query, cb) -> cb.conjunction());
	}

}
