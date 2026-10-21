package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.TenantMediaIntegrationTeacher;
import java.util.List;
import java.util.UUID;

/** Tenant-scoped per ADR-006 (the {@code TenantContext} filter applies to every finder). */
public interface TenantMediaIntegrationTeacherRepository
		extends TenantAwareRepository<TenantMediaIntegrationTeacher, UUID> {

	default List<TenantMediaIntegrationTeacher> findForIntegration(UUID integrationId) {
		return findAll((root, query, cb) -> cb.equal(root.get("integrationId"), integrationId));
	}

}
