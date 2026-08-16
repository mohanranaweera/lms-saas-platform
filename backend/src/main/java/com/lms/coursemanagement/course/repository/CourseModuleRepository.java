package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseModule;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006. See {@link CourseRepository}'s javadoc for why
 * every custom finder below is a {@code Specification}-backed default
 * method rather than a derived-query method.
 */
public interface CourseModuleRepository extends TenantAwareRepository<CourseModule, UUID> {

	default List<CourseModule> findByCourseId(UUID courseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId), Sort.by(Sort.Direction.ASC, "sequence"));
	}

	default Optional<CourseModule> findByIdAndCourseId(UUID id, UUID courseId) {
		return findOne(
				(root, query, cb) -> cb.and(cb.equal(root.get("id"), id), cb.equal(root.get("courseId"), courseId)));
	}

	default boolean existsByCourseIdAndSequence(UUID courseId, Integer sequence) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("courseId"), courseId),
				cb.equal(root.get("sequence"), sequence)));
	}

	default boolean existsByCourseIdAndSequenceAndIdNot(UUID courseId, Integer sequence, UUID excludedId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("courseId"), courseId),
				cb.equal(root.get("sequence"), sequence), cb.notEqual(root.get("id"), excludedId)));
	}

}
