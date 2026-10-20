package com.lms.enrollmentmanagement.repository;

import java.time.Instant;
import java.util.UUID;

/**
 * Custom Spring Data fragment of {@link LessonProgressRepository} (KLASS-T8b
 * SEC L2). The idempotent native insert lives in the package-private {@code
 * LessonProgressWritesImpl}, which reads the tenant from the resolved {@code
 * TenantContext} itself - no public repository method accepts a tenant id.
 */
public interface LessonProgressWrites {

	/**
	 * {@code INSERT ... ON CONFLICT DO NOTHING} in the resolved context tenant:
	 * the first authorized launch wins, every later launch is a no-op.
	 * @return {@code 1} on the first view, {@code 0} when already recorded.
	 */
	int recordFirstView(UUID studentId, UUID courseId, UUID lessonId, Instant viewedAt);

}
