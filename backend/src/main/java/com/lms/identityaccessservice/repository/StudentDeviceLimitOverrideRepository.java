package com.lms.identityaccessservice.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.identityaccessservice.domain.StudentDeviceLimitOverride;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped per ADR-006: at most one row per (tenant, user) (V64). */
public interface StudentDeviceLimitOverrideRepository
		extends TenantAwareRepository<StudentDeviceLimitOverride, UUID> {

	default Optional<StudentDeviceLimitOverride> findByUserId(UUID userId) {
		return findOne((root, query, cb) -> cb.equal(root.get("userId"), userId));
	}

}
