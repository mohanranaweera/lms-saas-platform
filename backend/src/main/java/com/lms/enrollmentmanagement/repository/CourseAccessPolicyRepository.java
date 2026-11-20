package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.enrollmentmanagement.domain.CourseAccessPolicy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped per ADR-006: at most one row per (tenant, course) (V65). */
public interface CourseAccessPolicyRepository extends TenantAwareRepository<CourseAccessPolicy, UUID> {

	default Optional<CourseAccessPolicy> findByCourseId(UUID courseId) {
		return findOne((root, query, cb) -> cb.equal(root.get("courseId"), courseId));
	}

	default List<CourseAccessPolicy> findByCourseIds(Collection<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("courseId").in(courseIds));
	}

}
