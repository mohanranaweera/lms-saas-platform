package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseTeacherAssignment;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** Tenant-scoped per ADR-006 (see {@link CourseRepository}'s javadoc). */
public interface CourseTeacherAssignmentRepository extends TenantAwareRepository<CourseTeacherAssignment, UUID> {

	/** Primary first, then by assignment time. */
	Sort DISPLAY_ORDER = Sort.by(Sort.Order.desc("primary"), Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

	default List<CourseTeacherAssignment> findByCourseId(UUID courseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId), DISPLAY_ORDER);
	}

	default List<CourseTeacherAssignment> findByCourseIdIn(Collection<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("courseId").in(courseIds), DISPLAY_ORDER);
	}

}
