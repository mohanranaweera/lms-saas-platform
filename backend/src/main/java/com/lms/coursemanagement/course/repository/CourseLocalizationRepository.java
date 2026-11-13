package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseLocalization;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006: every finder is a {@code default} method over the inherited,
 * structurally tenant-filtered {@code findAll(Specification)} family; no method accepts a tenant
 * id. Never used outside {@code com.lms.coursemanagement}.
 */
public interface CourseLocalizationRepository extends TenantAwareRepository<CourseLocalization, UUID> {

	default List<CourseLocalization> findByCourseId(UUID courseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("courseId"), courseId), Sort.by("locale"));
	}

	default Optional<CourseLocalization> findByCourseIdAndLocale(UUID courseId, String locale) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("courseId"), courseId),
				cb.equal(root.get("locale"), locale)));
	}

	/** PUBLISHED variants of the given courses (public storefront reads). */
	default List<CourseLocalization> findPublishedByCourseIdIn(Collection<UUID> courseIds) {
		if (courseIds == null || courseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(root.get("courseId").in(courseIds),
				cb.equal(root.get("status"), CourseLocalization.Status.PUBLISHED)));
	}

}
