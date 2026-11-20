package com.lms.exammanagement.repository;

import com.lms.common.persistence.UuidV7Generator;
import com.lms.common.tenant.TenantContext;
import com.lms.exammanagement.domain.ExamEnrollmentSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.util.UUID;

/**
 * Package-private implementation of {@link ExamEnrollmentWrites}. The tenant id comes only
 * from {@link TenantContext} (fails loudly when unresolved); absent optional ids are written
 * as SQL {@code NULL} literals so no untyped null parameter is ever bound.
 */
class ExamEnrollmentWritesImpl implements ExamEnrollmentWrites {

	private final TenantContext tenantContext;

	@PersistenceContext
	private EntityManager entityManager;

	ExamEnrollmentWritesImpl(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	@Override
	public int insertIfAbsent(UUID examId, UUID studentId, ExamEnrollmentSource source, UUID paymentId,
			UUID freeCardId, UUID actorId, Instant now) {
		String sql = "INSERT INTO exam_enrollment (id, tenant_id, exam_id, student_id, source, payment_id, "
				+ "free_card_id, created_at, updated_at, created_by, updated_by) VALUES (:id, :tenantId, :examId, "
				+ ":studentId, :source, " + (paymentId == null ? "NULL" : ":paymentId") + ", "
				+ (freeCardId == null ? "NULL" : ":freeCardId") + ", :now, :now, "
				+ (actorId == null ? "NULL, NULL" : ":actorId, :actorId")
				+ ") ON CONFLICT (tenant_id, exam_id, student_id) DO NOTHING";
		Query query = entityManager.createNativeQuery(sql)
			.setParameter("id", UuidV7Generator.generate())
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("examId", examId)
			.setParameter("studentId", studentId)
			.setParameter("source", source.name())
			.setParameter("now", now);
		if (paymentId != null) {
			query.setParameter("paymentId", paymentId);
		}
		if (freeCardId != null) {
			query.setParameter("freeCardId", freeCardId);
		}
		if (actorId != null) {
			query.setParameter("actorId", actorId);
		}
		return query.executeUpdate();
	}

}
