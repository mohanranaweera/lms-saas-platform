package com.lms.coursemanagement.course.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.coursemanagement.course.domain.CourseTagAssignment;
import com.lms.coursemanagement.course.domain.CourseTagAssignmentId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped per ADR-006 (see {@link CourseRepository}'s javadoc). */
public interface CourseTagAssignmentRepository
		extends TenantAwareRepository<CourseTagAssignment, CourseTagAssignmentId> {

	default List<CourseTagAssignment> findByCourseId(UUID courseId) {
		return findAll((root, query, cb) -> cb.equal(root.get("id").get("courseId"), courseId));
	}

	default List<CourseTagAssignment> findByCourseIdIn(Collection<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("id").get("courseId").in(courseIds));
	}

	/**
	 * R2-D6: {@code [courseId, tagId, tagName]} rows for every tag assigned to
	 * any of {@code courseIds}, in ONE query (assignment joined to tag), ordered
	 * by tag name. Tenant-scoped: the tenant id is read from the request's
	 * resolved {@link TenantContextHolder} here - never accepted from a caller -
	 * and applied to both tables, since a JPQL {@code @Query} bypasses
	 * {@code TenantAwareRepositoryImpl}'s structural filter.
	 */
	default List<Object[]> findTagRowsByCourseIds(Collection<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return findTagRowsByCourseIdsUnchecked(courseIds, new TenantContextHolder().getTenantId());
	}

	@Query("SELECT a.id.courseId, t.id, t.name FROM CourseTagAssignment a JOIN CourseTag t "
			+ "ON t.id = a.id.tagId AND t.tenantId = a.tenantId "
			+ "WHERE a.tenantId = :tenantId AND a.id.courseId IN :courseIds ORDER BY t.name, t.id")
	List<Object[]> findTagRowsByCourseIdsUnchecked(@Param("courseIds") Collection<UUID> courseIds,
			@Param("tenantId") UUID tenantId);

}
