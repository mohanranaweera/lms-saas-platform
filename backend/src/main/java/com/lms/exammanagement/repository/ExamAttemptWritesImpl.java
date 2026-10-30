package com.lms.exammanagement.repository;

import com.lms.common.tenant.TenantContext;
import com.lms.exammanagement.domain.ExamAttempt;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Package-private implementation of {@link ExamAttemptWrites}. */
class ExamAttemptWritesImpl implements ExamAttemptWrites {

	private final TenantContext tenantContext;

	@PersistenceContext
	private EntityManager entityManager;

	ExamAttemptWritesImpl(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	@Override
	public void lockAndRefresh(ExamAttempt attempt) {
		if (!attempt.getTenantId().equals(tenantContext.getTenantId())) {
			throw new IllegalStateException("Attempt does not belong to the current tenant");
		}
		entityManager.flush();
		entityManager.refresh(attempt, LockModeType.PESSIMISTIC_WRITE);
	}

	@Override
	public Grade gradeItems(UUID attemptId, Instant gradedAt) {
		UUID tenantId = tenantContext.getTenantId();
		entityManager.flush();
		entityManager.createNativeQuery("""
				UPDATE exam_attempt_item i
				SET awarded_marks = CASE WHEN EXISTS (
				        SELECT 1 FROM exam_paper_option o
				        WHERE o.tenant_id = i.tenant_id AND o.paper_item_id = i.paper_item_id
				          AND o.id = i.selected_option_id AND o.correct)
				    THEN p.marks ELSE 0 END,
				    updated_at = :now
				FROM exam_paper_item p
				WHERE i.tenant_id = :tenantId AND i.attempt_id = :attemptId AND i.awarded_marks IS NULL
				  AND p.tenant_id = i.tenant_id AND p.exam_id = i.exam_id AND p.id = i.paper_item_id
				""")
			.setParameter("tenantId", tenantId)
			.setParameter("attemptId", attemptId)
			.setParameter("now", Timestamp.from(gradedAt))
			.executeUpdate();
		Object[] row = (Object[]) entityManager.createNativeQuery("""
				SELECT COALESCE(SUM(awarded_marks), 0), COUNT(selected_option_id)
				FROM exam_attempt_item WHERE tenant_id = :tenantId AND attempt_id = :attemptId
				""")
			.setParameter("tenantId", tenantId)
			.setParameter("attemptId", attemptId)
			.getSingleResult();
		BigDecimal score = new BigDecimal(row[0].toString()).setScale(2, RoundingMode.UNNECESSARY);
		int answered = ((Number) row[1]).intValue();
		return new Grade(score, answered);
	}

	@Override
	public int saveAnswer(UUID attemptId, UUID itemId, UUID optionId, long revision, Instant now) {
		String sql = "UPDATE exam_attempt_item SET selected_option_id = " + (optionId == null ? "NULL" : ":optionId")
				+ ", revision = :revision, answered_at = " + (optionId == null ? "NULL" : ":now")
				+ ", updated_at = :now WHERE tenant_id = :tenantId AND attempt_id = :attemptId AND id = :itemId"
				+ " AND revision < :revision AND awarded_marks IS NULL";
		Query query = entityManager.createNativeQuery(sql)
			.setParameter("revision", revision)
			.setParameter("now", Timestamp.from(now))
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("attemptId", attemptId)
			.setParameter("itemId", itemId);
		if (optionId != null) {
			query.setParameter("optionId", optionId);
		}
		return query.executeUpdate();
	}

	@Override
	public long currentRevision(UUID attemptId, UUID itemId) {
		Object value = entityManager.createNativeQuery("""
				SELECT revision FROM exam_attempt_item
				WHERE tenant_id = :tenantId AND attempt_id = :attemptId AND id = :itemId
				""")
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("attemptId", attemptId)
			.setParameter("itemId", itemId)
			.getSingleResult();
		return ((Number) value).longValue();
	}

	@Override
	@SuppressWarnings("unchecked")
	public List<OverdueAttempt> findOverdueKlassAttemptsAcrossTenants(Instant now, int limit) {
		List<Object[]> rows = entityManager.createNativeQuery("""
				SELECT tenant_id, id FROM exam_attempt
				WHERE klass_attempt AND status = 'IN_PROGRESS' AND deadline_at <= :now
				ORDER BY deadline_at
				LIMIT :limit
				""")
			.setParameter("now", Timestamp.from(now))
			.setParameter("limit", limit)
			.getResultList();
		return rows.stream().map(row -> new OverdueAttempt((UUID) row[0], (UUID) row[1])).toList();
	}

	@Override
	public int prunePasswordFailuresAcrossTenants(Instant before) {
		return entityManager.createNativeQuery("DELETE FROM exam_password_failure WHERE failed_at < :before")
			.setParameter("before", Timestamp.from(before))
			.executeUpdate();
	}

}
