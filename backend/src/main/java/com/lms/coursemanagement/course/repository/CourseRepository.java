package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.domain.CourseStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006/{@code .claude/rules/tenancy.md}. Every custom
 * finder below is a default method built on {@link
 * org.springframework.data.jpa.domain.Specification}-backed {@code
 * findOne}/{@code findAll}/{@code exists} (inherited from {@link
 * TenantAwareRepository}), never a Spring Data derived-query method - the
 * latter would bypass {@code TenantAwareRepositoryImpl}'s tenant-scoping
 * override, mirroring {@code identityaccessservice.repository
 * .TenantUserRepository#findByEmail}'s established pattern exactly.
 */
public interface CourseRepository extends TenantAwareRepository<Course, UUID> {

	default Optional<Course> findBySlug(String slug) {
		return findOne((root, query, cb) -> cb.equal(root.get("slug"), slug));
	}

	default boolean existsBySlug(String slug) {
		return exists((root, query, cb) -> cb.equal(root.get("slug"), slug));
	}

	default boolean existsBySlugAndIdNot(String slug, UUID excludedId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("slug"), slug), cb.notEqual(root.get("id"), excludedId)));
	}

	/**
	 * Storefront detail read path. A DRAFT/PRIVATE course in the same tenant
	 * as a guessed slug resolves to empty here (not merely filtered after
	 * fetch), so the controller's 404 for "wrong status" and "nonexistent
	 * slug" are structurally the same code path.
	 */
	default Optional<Course> findBySlugAndStatus(String slug, CourseStatus status) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("slug"), slug), cb.equal(root.get("status"), status)));
	}

}
