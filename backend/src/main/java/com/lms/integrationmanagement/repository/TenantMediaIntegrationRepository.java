package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.domain.TenantMediaIntegration;
import com.lms.integrationmanagement.domain.TenantMediaIntegration.Status;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006: every finder runs through the {@code
 * TenantContext} filter, so another tenant's id is simply not found. No
 * delete method - deleting an integration is a status change (V76).
 */
public interface TenantMediaIntegrationRepository extends TenantAwareRepository<TenantMediaIntegration, UUID> {

	/** The tenant's integrations for a provider in the given statuses, oldest first (card / tab order). */
	default List<TenantMediaIntegration> findForProvider(MediaProvider provider, List<Status> statuses) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("provider"), provider),
				root.get("status").in(statuses)), Sort.by(Sort.Direction.ASC, "createdAt"));
	}

	default long countActive(MediaProvider provider) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("provider"), provider),
				cb.equal(root.get("status"), Status.ACTIVE)));
	}

}
