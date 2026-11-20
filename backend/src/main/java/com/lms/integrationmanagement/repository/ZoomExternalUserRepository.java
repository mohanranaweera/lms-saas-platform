package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.ZoomExternalUser;
import com.lms.integrationmanagement.domain.ZoomExternalUserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

public interface ZoomExternalUserRepository extends TenantAwareRepository<ZoomExternalUser, UUID> {

	default List<ZoomExternalUser> findForIntegration(UUID integrationId) {
		return findAll((root, query, cb) -> cb.equal(root.get("integrationId"), integrationId),
			Sort.by(Sort.Direction.ASC, "displayName"));
	}

	default Optional<ZoomExternalUser> findForIntegrationById(UUID integrationId, UUID externalUserId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("id"), externalUserId),
				cb.equal(root.get("integrationId"), integrationId)));
	}

	default Optional<ZoomExternalUser> findByProviderUserId(UUID integrationId, String zoomUserId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("integrationId"), integrationId),
				cb.equal(root.get("zoomUserId"), zoomUserId)));
	}

	default long countActiveForIntegration(UUID integrationId) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("integrationId"), integrationId),
				cb.equal(root.get("status"), ZoomExternalUserStatus.ACTIVE)));
	}

}
