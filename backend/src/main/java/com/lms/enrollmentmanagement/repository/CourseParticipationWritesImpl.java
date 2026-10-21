package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.UuidV7Generator;
import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.domain.CourseParticipationSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;

/**
 * Package-private implementation of {@link CourseParticipationWrites}. The
 * tenant id comes only from {@link TenantContext} (which fails loudly when
 * unresolved); it is never a method parameter. Runs in the caller's
 * (service-layer) transaction, like the {@code @Modifying} query it replaced.
 */
class CourseParticipationWritesImpl implements CourseParticipationWrites {

	private final TenantContext tenantContext;

	@PersistenceContext
	private EntityManager entityManager;

	CourseParticipationWritesImpl(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	@Override
	public int insertIfAbsent(UUID studentId, UUID courseId, CourseParticipationSource source, Instant enrolledAt) {
		return entityManager.createNativeQuery("""
				INSERT INTO course_participation (id, tenant_id, student_id, course_id, enrolled_at, source)
				VALUES (:id, :tenantId, :studentId, :courseId, :enrolledAt, :source)
				ON CONFLICT (tenant_id, student_id, course_id) DO NOTHING
				""")
			.setParameter("id", UuidV7Generator.generate())
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("studentId", studentId)
			.setParameter("courseId", courseId)
			.setParameter("enrolledAt", enrolledAt)
			.setParameter("source", source.name())
			.executeUpdate();
	}

}
