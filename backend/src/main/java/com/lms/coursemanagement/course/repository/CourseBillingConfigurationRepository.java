package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.CourseBillingConfiguration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. See {@link CourseRepository}'s javadoc for why
 * the custom finders below are {@code Specification}-backed default methods
 * rather than derived-query methods.
 */
public interface CourseBillingConfigurationRepository extends TenantAwareRepository<CourseBillingConfiguration, UUID> {

	default Optional<CourseBillingConfiguration> findByCourseId(UUID courseId) {
		return findOne((root, query, cb) -> cb.equal(root.get("courseId"), courseId));
	}

	/**
	 * Batched sibling of {@link #findByCourseId(UUID)} - resolves every
	 * configuration for the given course ids in a single query, so a
	 * multi-course read path (e.g. a storefront listing page) never calls
	 * {@link #findByCourseId(UUID)} once per course in a loop. Added for
	 * {@code CourseCheckoutAmountResolver#resolveBatch}, mirroring {@code
	 * CourseLookupApi#getCourseSummaries(Set)}'s existing batching precedent.
	 */
	default List<CourseBillingConfiguration> findByCourseIdIn(Collection<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("courseId").in(courseIds));
	}

}
