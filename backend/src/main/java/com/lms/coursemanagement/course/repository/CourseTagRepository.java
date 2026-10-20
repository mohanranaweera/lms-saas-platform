package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseTag;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/**
 * Tenant-scoped per ADR-006 - every finder is a {@code Specification}-backed
 * default method so {@code TenantAwareRepositoryImpl} ANDs in the tenant
 * predicate (see {@link CourseRepository}'s javadoc).
 */
public interface CourseTagRepository extends TenantAwareRepository<CourseTag, UUID> {

	default List<CourseTag> findAllOrderedByName() {
		return findAll(Sort.by(Sort.Direction.ASC, "name"));
	}

	default List<CourseTag> findByNormalizedNameIn(Collection<String> normalizedNames) {
		if (normalizedNames.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("normalizedName").in(normalizedNames));
	}

}
