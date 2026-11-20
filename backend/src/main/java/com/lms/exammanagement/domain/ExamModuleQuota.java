package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Mapped onto {@code exam_module_quota} (V111): how many questions an MCQ Klass exam draws
 * from one module of its course. {@code courseId} is carried so the schema can enforce
 * that the module belongs to the exam's own course (composite FKs). Replaced wholesale
 * whenever the exam's quotas change - never edited in place.
 */
@Entity
@Table(name = "exam_module_quota")
public class ExamModuleQuota extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "course_id", nullable = false, updatable = false)
	private UUID courseId;

	@Column(name = "module_id", nullable = false, updatable = false)
	private UUID moduleId;

	@Column(name = "question_count", nullable = false, updatable = false)
	private int questionCount;

	protected ExamModuleQuota() {
	}

	public ExamModuleQuota(UUID tenantId, UUID examId, UUID courseId, UUID moduleId, int questionCount) {
		this.tenantId = tenantId;
		this.examId = examId;
		this.courseId = courseId;
		this.moduleId = moduleId;
		this.questionCount = questionCount;
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

	public UUID getCourseId() {
		return courseId;
	}

	public UUID getModuleId() {
		return moduleId;
	}

	public int getQuestionCount() {
		return questionCount;
	}

}
