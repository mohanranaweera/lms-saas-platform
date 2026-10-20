package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.enrollmentmanagement.domain.LessonProgress;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tenant-scoped (KLASS-T4b, D14). {@link LessonProgressWrites#recordFirstView}
 * is an idempotent {@code INSERT ... ON CONFLICT DO NOTHING}: the first
 * authorized launch wins, every later launch is a no-op. Its package-private
 * fragment implementation reads the tenant from the resolved context - no
 * method of this repository accepts a tenant id (KLASS-T8b SEC L2).
 */
public interface LessonProgressRepository
		extends AppendOnlyTenantAwareRepository<LessonProgress, UUID>, LessonProgressWrites {

	default Set<UUID> findViewedLessonIds(UUID studentId, Collection<UUID> lessonIds) {
		if (lessonIds.isEmpty()) {
			return Set.of();
		}
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				root.get("lessonId").in(lessonIds)))
			.stream()
			.map(LessonProgress::getLessonId)
			.collect(Collectors.toSet());
	}

}
