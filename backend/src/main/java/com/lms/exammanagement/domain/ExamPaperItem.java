package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * One frozen question of an MCQ Klass exam's paper ({@code exam_paper_item}, V112, D3): a
 * copy of the bank question's sanitized body, marks and explanation at draw time. Never
 * updated (DB trigger); a redraw before the first attempt deletes and re-inserts the whole
 * paper. Contains the answer key via its options - staff-only data.
 */
@Entity
@Immutable
@Table(name = "exam_paper_item")
public class ExamPaperItem extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "sequence", nullable = false, updatable = false)
	private int sequence;

	@Column(name = "source_question_id", nullable = false, updatable = false)
	private UUID sourceQuestionId;

	@Column(name = "body_html", nullable = false, updatable = false)
	private String bodyHtml;

	@Column(name = "marks", nullable = false, updatable = false, precision = 6, scale = 2)
	private BigDecimal marks;

	@Column(name = "explanation_html", updatable = false)
	private String explanationHtml;

	protected ExamPaperItem() {
	}

	public ExamPaperItem(UUID tenantId, UUID examId, int sequence, UUID sourceQuestionId, String bodyHtml,
			BigDecimal marks, String explanationHtml) {
		this.tenantId = tenantId;
		this.examId = examId;
		this.sequence = sequence;
		this.sourceQuestionId = sourceQuestionId;
		this.bodyHtml = bodyHtml;
		this.marks = marks;
		this.explanationHtml = explanationHtml;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getExamId() {
		return examId;
	}

	public int getSequence() {
		return sequence;
	}

	public UUID getSourceQuestionId() {
		return sourceQuestionId;
	}

	public String getBodyHtml() {
		return bodyHtml;
	}

	public BigDecimal getMarks() {
		return marks;
	}

	public String getExplanationHtml() {
		return explanationHtml;
	}

}
