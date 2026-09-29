package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.TenantMediaIntegrationSettings;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped (ADR-006) - at most one row per tenant (V60). */
public interface TenantMediaIntegrationSettingsRepository
		extends TenantAwareRepository<TenantMediaIntegrationSettings, UUID> {

	default Optional<TenantMediaIntegrationSettings> findForCurrentTenant() {
		return findOne((root, query, cb) -> cb.conjunction());
	}

}
