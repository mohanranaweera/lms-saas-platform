package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.enrollmentmanagement.domain.CourseParticipation;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tenant-scoped per ADR-006 (KLASS-T4b, D01). Reads are {@code default}
 * methods on the inherited, structurally tenant-filtered {@code findOne}/
 * {@code findAll}/{@code exists}. The only write is {@link
 * CourseParticipationWrites#insertIfAbsent}, a race-safe {@code INSERT ... ON
 * CONFLICT DO NOTHING} against {@code
 * uq_course_participation_tenant_student_course}: two concurrent self-enrolls
 * end with exactly one row, the loser's insert affecting 0 rows. No method of
 * this repository accepts a tenant id - the package-private fragment
 * implementation reads it from the resolved tenant context (KLASS-T8b SEC
 * L2). Rows are never deleted (append-only).
 */
public interface CourseParticipationRepository
		extends AppendOnlyTenantAwareRepository<CourseParticipation, UUID>, CourseParticipationWrites {

	default Optional<CourseParticipation> findByStudentIdAndCourseId(UUID studentId, UUID courseId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("courseId"), courseId)));
	}

	default boolean existsByStudentIdAndCourseIdIn(UUID studentId, Collection<UUID> courseIds) {
		if (courseIds.isEmpty()) {
			return false;
		}
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				root.get("courseId").in(courseIds)));
	}

	default List<CourseParticipation> findAllByStudentId(UUID studentId) {
		return findAll((root, query, cb) -> cb.equal(root.get("studentId"), studentId));
	}

	default Set<UUID> findCourseIdsByStudentId(UUID studentId) {
		return findAllByStudentId(studentId).stream()
			.map(CourseParticipation::getCourseId)
			.collect(Collectors.toSet());
	}

}
