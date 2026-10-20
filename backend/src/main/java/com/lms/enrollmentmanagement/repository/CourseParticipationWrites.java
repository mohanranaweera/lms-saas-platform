package com.lms.enrollmentmanagement.repository;

import com.lms.enrollmentmanagement.domain.CourseParticipationSource;
import java.time.Instant;
import java.util.UUID;

/**
 * Custom Spring Data fragment of {@link CourseParticipationRepository}
 * (KLASS-T8b SEC L2). The race-safe native insert lives in the package-private
 * {@code CourseParticipationWritesImpl}, which reads the tenant from the
 * resolved {@code TenantContext} itself - no public repository method accepts a
 * tenant id.
 */
public interface CourseParticipationWrites {

	/**
	 * {@code INSERT ... ON CONFLICT DO NOTHING} against {@code
	 * uq_course_participation_tenant_student_course}, in the resolved context
	 * tenant.
	 * @return {@code 1} if a new row was inserted, {@code 0} if the student
	 * already participates in the course (idempotent no-op).
	 */
	int insertIfAbsent(UUID studentId, UUID courseId, CourseParticipationSource source, Instant enrolledAt);

}
