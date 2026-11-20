package com.lms.integrationmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.integrationmanagement.domain.ZoomMappingStatus;
import com.lms.integrationmanagement.domain.ZoomTeacherUserMapping;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

public interface ZoomTeacherUserMappingRepository extends TenantAwareRepository<ZoomTeacherUserMapping, UUID> {

	default List<ZoomTeacherUserMapping> findForIntegration(UUID integrationId) {
		return findAll((root, query, cb) -> cb.equal(root.get("integrationId"), integrationId),
			Sort.by(Sort.Direction.ASC, "mappedAt"));
	}

	default Optional<ZoomTeacherUserMapping> findActiveForTeacher(UUID integrationId, UUID teacherUserId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("integrationId"), integrationId),
				cb.equal(root.get("teacherUserId"), teacherUserId),
				cb.equal(root.get("status"), ZoomMappingStatus.ACTIVE)));
	}

	default Optional<ZoomTeacherUserMapping> findActiveForExternalUser(UUID integrationId, UUID externalUserId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("integrationId"), integrationId),
				cb.equal(root.get("zoomExternalUserId"), externalUserId),
				cb.equal(root.get("status"), ZoomMappingStatus.ACTIVE)));
	}

}
