package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseLesson;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006. See {@link CourseRepository}'s javadoc for why
 * every custom finder below is a {@code Specification}-backed default
 * method rather than a derived-query method.
 */
public interface CourseLessonRepository extends TenantAwareRepository<CourseLesson, UUID> {

	default List<CourseLesson> findByModuleId(UUID moduleId) {
		return findAll((root, query, cb) -> cb.equal(root.get("moduleId"), moduleId), Sort.by(Sort.Direction.ASC, "sequence"));
	}

	default Optional<CourseLesson> findByIdAndModuleId(UUID id, UUID moduleId) {
		return findOne(
				(root, query, cb) -> cb.and(cb.equal(root.get("id"), id), cb.equal(root.get("moduleId"), moduleId)));
	}

	default boolean existsByModuleIdAndSequence(UUID moduleId, Integer sequence) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("moduleId"), moduleId),
				cb.equal(root.get("sequence"), sequence)));
	}

	default boolean existsByModuleIdAndSequenceAndIdNot(UUID moduleId, Integer sequence, UUID excludedId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("moduleId"), moduleId),
				cb.equal(root.get("sequence"), sequence), cb.notEqual(root.get("id"), excludedId)));
	}

}
