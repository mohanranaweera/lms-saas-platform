package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code exam_question_link} (V26) - the ordered bridge row
 * between {@link Exam} and {@link ExamQuestion}. {@code examId}/{@code
 * questionId} are bare structural references, never JPA associations (mirrors
 * {@code course-management}'s {@code CourseModule}/{@code CourseLesson}
 * precedent).
 *
 * <p>{@code ExamSchedulingService#updateDraftExam} replaces this exam's
 * entire link set on every edit (delete-all then re-insert with fresh
 * sequence numbers 1..N) rather than diffing/reordering in place - the
 * simplest way to respect {@code
 * uq_exam_question_link_tenant_exam_sequence}'s uniqueness constraint without
 * a transient collision window.
 */
@Entity
@Table(name = "exam_question_link")
public class ExamQuestionLink extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "question_id", nullable = false, updatable = false)
	private UUID questionId;

	@Column(name = "sequence", nullable = false)
	private Integer sequence;

	protected ExamQuestionLink() {
	}

	public ExamQuestionLink(UUID tenantId, UUID examId, UUID questionId, Integer sequence) {
		this.tenantId = tenantId;
		this.examId = examId;
		this.questionId = questionId;
		this.sequence = sequence;
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

	public UUID getQuestionId() {
		return questionId;
	}

	public Integer getSequence() {
		return sequence;
	}

}
