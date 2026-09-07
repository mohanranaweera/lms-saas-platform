package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code exam_question_option} (V26). {@code questionId} is a
 * bare structural-child reference to {@link ExamQuestion}'s id (never a JPA
 * association even though both entities belong to this same module - mirrors
 * {@code course-management}'s {@code CourseModule}/{@code CourseLesson}
 * precedent of always referencing a parent by id, never by lazy-loaded
 * association).
 *
 * <p>{@code isCorrect} is read ONLY by {@link
 * com.lms.exammanagement.service.McqAutoMarkingService} - it must never be
 * serialized into any response DTO reachable by a Student or a pre-submission
 * Teacher/staff read (MVP-017 plan §10/§15).
 */
@Entity
@Table(name = "exam_question_option")
public class ExamQuestionOption extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "question_id", nullable = false, updatable = false)
	private UUID questionId;

	@Column(name = "option_text", nullable = false)
	private String optionText;

	@Column(name = "is_correct", nullable = false)
	private boolean isCorrect;

	protected ExamQuestionOption() {
	}

	public ExamQuestionOption(UUID tenantId, UUID questionId, String optionText, boolean isCorrect) {
		this.tenantId = tenantId;
		this.questionId = questionId;
		this.optionText = optionText;
		this.isCorrect = isCorrect;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getQuestionId() {
		return questionId;
	}

	public String getOptionText() {
		return optionText;
	}

	public boolean isCorrect() {
		return isCorrect;
	}

}
