package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code exam_submission_document} (V114): one WRITTEN exam answer sheet ({@code
 * EXAM_ANSWER_SHEET} media) of an enrolled student, uploaded by the student or by staff on the
 * student's behalf. Never hard-deleted: a staff removal before publication sets {@code
 * deletedAt}/{@code deletedBy} once (schema-enforced).
 */
@Entity
@Table(name = "exam_submission_document")
public class ExamSubmissionDocument extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "media_id", nullable = false, updatable = false)
	private UUID mediaId;

	@Column(name = "uploaded_by", nullable = false, updatable = false)
	private UUID uploadedBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "uploaded_by_role", nullable = false, updatable = false, length = 7)
	private ExamSubmissionUploaderRole uploadedByRole;

	@Column(name = "uploaded_at", nullable = false, updatable = false)
	private Instant uploadedAt;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by")
	private UUID deletedBy;

	protected ExamSubmissionDocument() {
	}

	public ExamSubmissionDocument(UUID tenantId, UUID examId, UUID studentId, UUID mediaId, UUID uploadedBy,
			ExamSubmissionUploaderRole uploadedByRole, Instant uploadedAt) {
		this.tenantId = tenantId;
		this.examId = examId;
		this.studentId = studentId;
		this.mediaId = mediaId;
		this.uploadedBy = uploadedBy;
		this.uploadedByRole = uploadedByRole;
		this.uploadedAt = uploadedAt;
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

	public UUID getStudentId() {
		return studentId;
	}

	public UUID getMediaId() {
		return mediaId;
	}

	public UUID getUploadedBy() {
		return uploadedBy;
	}

	public ExamSubmissionUploaderRole getUploadedByRole() {
		return uploadedByRole;
	}

	public Instant getUploadedAt() {
		return uploadedAt;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public boolean isDeleted() {
		return deletedAt != null;
	}

	public void markDeleted(UUID actorId, Instant at) {
		this.deletedAt = at;
		this.deletedBy = actorId;
	}

}
