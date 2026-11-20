package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseLink;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** Tenant-scoped per ADR-006 (see {@link CourseRepository}'s javadoc). */
public interface CourseLinkRepository extends TenantAwareRepository<CourseLink, UUID> {

	Sort CREATION_ORDER = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

	default List<CourseLink> findByParentCourseId(UUID parentCourseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("parentCourseId"), parentCourseId), CREATION_ORDER);
	}

	default List<CourseLink> findByParentCourseIdIn(Collection<UUID> parentCourseIds) {
		if (parentCourseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("parentCourseId").in(parentCourseIds), CREATION_ORDER);
	}

	/** Is {@code courseId} linked INTO some other course (i.e. a link source)? */
	default boolean existsBySourceCourseId(UUID courseId) {
		return exists((root, query, cb) -> cb.equal(root.get("sourceCourseId"), courseId));
	}

}
