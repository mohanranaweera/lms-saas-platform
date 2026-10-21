package com.lms.identityaccessservice.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.identityaccessservice.domain.TenantDevicePolicy;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped per ADR-006: at most one row per tenant (UNIQUE tenant_id, V64). */
public interface TenantDevicePolicyRepository extends TenantAwareRepository<TenantDevicePolicy, UUID> {

	/** The current tenant policy row, if the tenant has ever saved one. */
	default Optional<TenantDevicePolicy> findForCurrentTenant() {
		return findOne((root, query, cb) -> cb.conjunction());
	}

}
