package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.UuidV7Generator;
import com.lms.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;

/**
 * Package-private implementation of {@link LessonProgressWrites}. The tenant
 * id comes only from {@link TenantContext} (which fails loudly when
 * unresolved); it is never a method parameter. Runs in the caller's
 * (service-layer) transaction, like the {@code @Modifying} query it replaced.
 */
class LessonProgressWritesImpl implements LessonProgressWrites {

	private final TenantContext tenantContext;

	@PersistenceContext
	private EntityManager entityManager;

	LessonProgressWritesImpl(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	@Override
	public int recordFirstView(UUID studentId, UUID courseId, UUID lessonId, Instant viewedAt) {
		return entityManager.createNativeQuery("""
				INSERT INTO lesson_progress (id, tenant_id, student_id, course_id, lesson_id, first_viewed_at)
				VALUES (:id, :tenantId, :studentId, :courseId, :lessonId, :viewedAt)
				ON CONFLICT (tenant_id, student_id, lesson_id) DO NOTHING
				""")
			.setParameter("id", UuidV7Generator.generate())
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("studentId", studentId)
			.setParameter("courseId", courseId)
			.setParameter("lessonId", lessonId)
			.setParameter("viewedAt", viewedAt)
			.executeUpdate();
	}

}
