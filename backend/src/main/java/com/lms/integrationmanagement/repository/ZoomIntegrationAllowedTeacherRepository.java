package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.ZoomIntegrationAllowedTeacher;
import java.util.List;
import java.util.UUID;

public interface ZoomIntegrationAllowedTeacherRepository
		extends TenantAwareRepository<ZoomIntegrationAllowedTeacher, UUID> {

	default List<ZoomIntegrationAllowedTeacher> findForIntegration(UUID integrationId) {
		return findAll((root, query, cb) -> cb.equal(root.get("integrationId"), integrationId));
	}

	default boolean existsForIntegrationAndTeacher(UUID integrationId, UUID teacherUserId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("integrationId"), integrationId),
				cb.equal(root.get("teacherUserId"), teacherUserId)));
	}

}
