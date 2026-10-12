package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.ZoomIntegration;
import com.lms.integrationmanagement.domain.ZoomIntegrationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

public interface ZoomIntegrationRepository extends TenantAwareRepository<ZoomIntegration, UUID> {

	default List<ZoomIntegration> findAllVisible() {
		return findAll((root, query, cb) -> cb.notEqual(root.get("status"), ZoomIntegrationStatus.DELETED),
			Sort.by(Sort.Direction.ASC, "createdAt"));
	}

	default Optional<ZoomIntegration> findVisibleById(UUID integrationId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("id"), integrationId),
				cb.notEqual(root.get("status"), ZoomIntegrationStatus.DELETED)));
	}

	default long countVisible() {
		return count((root, query, cb) -> cb.notEqual(root.get("status"), ZoomIntegrationStatus.DELETED));
	}

}
