package com.lms.usermanagement.teacher.storefront.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.usermanagement.teacher.storefront.domain.TeacherStorefront;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006: every finder is AND-combined with the resolved
 * tenant context by {@code TenantAwareRepositoryImpl}; no method accepts a
 * tenant id.
 */
public interface TeacherStorefrontRepository extends TenantAwareRepository<TeacherStorefront, UUID> {

	default Optional<TeacherStorefront> findByTeacherId(UUID teacherId) {
		return findOne((root, query, cb) -> cb.equal(root.get("teacherId"), teacherId));
	}

	default Optional<TeacherStorefront> findBySlug(String slug) {
		return findOne((root, query, cb) -> cb.equal(root.get("slug"), slug));
	}

	default List<TeacherStorefront> findByTeacherIdIn(Collection<UUID> teacherIds) {
		if (teacherIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("teacherId").in(teacherIds));
	}

}
