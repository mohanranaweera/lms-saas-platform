package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code exam_question} (V26). {@code courseId} is an OPAQUE
 * {@code course-management} id only - never a JPA association across the
 * module boundary (per {@code .claude/rules/architecture.md}), though it is
 * still schema-enforced via V26's composite {@code fk_exam_question_course}
 * FK. Reusable content: a question is immediately available across multiple
 * exams in its course once created (MVP-017 plan §4 Flow A) - there is no
 * per-question draft/publish state in this schema.
 *
 * <p>{@code questionType} is fixed at creation and never changed by {@link
 * com.lms.exammanagement.service.QuestionBankService#updateQuestion} - only
 * {@code body} (and, for MCQ, the linked {@code exam_question_option} rows)
 * may be edited. Changing a question's type after creation would leave stale
 * options/answers inconsistent with the new type, which this module does not
 * attempt to reconcile.
 */
@Entity
@Table(name = "exam_question")
public class ExamQuestion extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "question_type", nullable = false, length = 20, updatable = false)
	private QuestionType questionType;

	@Column(name = "body", nullable = false)
	private String body;

	protected ExamQuestion() {
	}

	public ExamQuestion(UUID tenantId, UUID courseId, QuestionType questionType, String body) {
		this.tenantId = tenantId;
		this.courseId = courseId;
		this.questionType = questionType;
		this.body = body;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getCourseId() {
		return courseId;
	}

	public QuestionType getQuestionType() {
		return questionType;
	}

	public String getBody() {
		return body;
	}

	public void setBody(String body) {
		this.body = body;
	}

}
