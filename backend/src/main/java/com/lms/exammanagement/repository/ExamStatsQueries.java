package com.lms.exammanagement.repository;

import com.lms.common.tenant.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Aggregate read queries behind the Klass exam stats page (contract "Slice E"). Each method is a
 * single grouped SQL statement over the frozen paper and the <em>finalized, graded</em> Klass
 * attempts of one exam - never a per-student or per-attempt load. Tenant scoping: the tenant id
 * always comes from the resolved {@link TenantContext}, never from a caller.
 */
@Repository
public class ExamStatsQueries {

	private final TenantContext tenantContext;

	@PersistenceContext
	private EntityManager entityManager;

	public ExamStatsQueries(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	/** Per paper item: correct / incorrect / unanswered over finalized graded attempts, in paper order. */
	public List<QuestionCounts> questionCounts(UUID examId) {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createNativeQuery("""
				SELECT p.id, p.sequence, p.body_html, p.marks,
				       COUNT(a.id) FILTER (WHERE i.selected_option_id IS NOT NULL AND o.correct),
				       COUNT(a.id) FILTER (WHERE i.selected_option_id IS NOT NULL AND NOT o.correct),
				       COUNT(a.id) FILTER (WHERE i.selected_option_id IS NULL)
				FROM exam_paper_item p
				LEFT JOIN exam_attempt_item i
				       ON i.tenant_id = p.tenant_id AND i.exam_id = p.exam_id AND i.paper_item_id = p.id
				LEFT JOIN exam_attempt a
				       ON a.tenant_id = i.tenant_id AND a.exam_id = i.exam_id AND a.id = i.attempt_id
				      AND a.klass_attempt AND a.status <> 'IN_PROGRESS' AND a.graded_at IS NOT NULL
				LEFT JOIN exam_paper_option o
				       ON o.tenant_id = i.tenant_id AND o.paper_item_id = i.paper_item_id
				      AND o.id = i.selected_option_id
				WHERE p.tenant_id = :tenantId AND p.exam_id = :examId
				GROUP BY p.id, p.sequence, p.body_html, p.marks
				ORDER BY p.sequence
				""")
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("examId", examId)
			.getResultList();
		List<QuestionCounts> result = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			result.add(new QuestionCounts((UUID) row[0], ((Number) row[1]).intValue(), (String) row[2],
					new BigDecimal(row[3].toString()), ((Number) row[4]).longValue(), ((Number) row[5]).longValue(),
					((Number) row[6]).longValue()));
		}
		return result;
	}

	/** Per paper option: how many finalized graded attempts selected it, in paper / option order. */
	public List<OptionCounts> optionCounts(UUID examId) {
		@SuppressWarnings("unchecked")
		List<Object[]> rows = entityManager.createNativeQuery("""
				SELECT o.paper_item_id, o.id, o.html, o.correct, COUNT(a.id)
				FROM exam_paper_option o
				JOIN exam_paper_item p ON p.tenant_id = o.tenant_id AND p.id = o.paper_item_id
				LEFT JOIN exam_attempt_item i
				       ON i.tenant_id = o.tenant_id AND i.paper_item_id = o.paper_item_id
				      AND i.selected_option_id = o.id
				LEFT JOIN exam_attempt a
				       ON a.tenant_id = i.tenant_id AND a.exam_id = i.exam_id AND a.id = i.attempt_id
				      AND a.klass_attempt AND a.status <> 'IN_PROGRESS' AND a.graded_at IS NOT NULL
				WHERE p.tenant_id = :tenantId AND p.exam_id = :examId
				GROUP BY o.paper_item_id, o.id, o.html, o.correct, p.sequence, o.sequence
				ORDER BY p.sequence, o.sequence
				""")
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("examId", examId)
			.getResultList();
		List<OptionCounts> result = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			result.add(new OptionCounts((UUID) row[0], (UUID) row[1], (String) row[2], (Boolean) row[3],
					((Number) row[4]).longValue()));
		}
		return result;
	}

	/** Overdue open Klass attempts of one exam (to finalize before reading results). */
	public List<UUID> overdueOpenAttemptIds(UUID examId, java.time.Instant now) {
		@SuppressWarnings("unchecked")
		List<UUID> ids = entityManager.createNativeQuery("""
				SELECT id FROM exam_attempt
				WHERE tenant_id = :tenantId AND exam_id = :examId AND klass_attempt
				  AND status = 'IN_PROGRESS' AND deadline_at <= :now
				""")
			.setParameter("tenantId", tenantContext.getTenantId())
			.setParameter("examId", examId)
			.setParameter("now", java.sql.Timestamp.from(now))
			.getResultList();
		return ids;
	}

	public record QuestionCounts(UUID paperItemId, int sequence, String bodyHtml, BigDecimal marks, long correct,
			long incorrect, long unanswered) {
	}

	public record OptionCounts(UUID paperItemId, UUID optionId, String html, boolean correct, long selectedCount) {
	}

}
