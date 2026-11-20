package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.enrollmentmanagement.domain.TenantAccessPolicy;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped per ADR-006: at most one row per tenant (V65). */
public interface TenantAccessPolicyRepository extends TenantAwareRepository<TenantAccessPolicy, UUID> {

	default Optional<TenantAccessPolicy> findForCurrentTenant() {
		return findOne((root, query, cb) -> cb.conjunction());
	}

}
