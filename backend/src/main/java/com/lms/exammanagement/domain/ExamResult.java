package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code exam_result} (V114): the staff-recorded result of one enrolled student in a WRITTEN or
 * PHYSICAL Klass exam - attendance (physical), marks and the marked paper (written). Requires an
 * exam enrollment (composite FK); never deleted; every change is audited by {@code
 * ExamMarkingService} in the same transaction. MCQ scores live on {@code exam_attempt}.
 */
@Entity
@Table(name = "exam_result")
public class ExamResult extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "attendance", length = 7)
	private ExamAttendance attendance;

	@Column(name = "marks", precision = 8, scale = 2)
	private BigDecimal marks;

	@Column(name = "marked_paper_media_id")
	private UUID markedPaperMediaId;

	@Column(name = "marked_by")
	private UUID markedBy;

	@Column(name = "marked_at")
	private Instant markedAt;

	protected ExamResult() {
	}

	public ExamResult(UUID tenantId, UUID examId, UUID studentId) {
		this.tenantId = tenantId;
		this.examId = examId;
		this.studentId = studentId;
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

	public ExamAttendance getAttendance() {
		return attendance;
	}

	public BigDecimal getMarks() {
		return marks;
	}

	public UUID getMarkedPaperMediaId() {
		return markedPaperMediaId;
	}

	public UUID getMarkedBy() {
		return markedBy;
	}

	public Instant getMarkedAt() {
		return markedAt;
	}

	public boolean isGraded() {
		return marks != null;
	}

	/** Records marks / attendance set by {@code actorId}; the caller has validated and audits. */
	public void mark(BigDecimal marks, ExamAttendance attendance, UUID actorId, Instant at) {
		this.marks = marks;
		this.attendance = attendance;
		this.markedBy = actorId;
		this.markedAt = at;
	}

	public void attachMarkedPaper(UUID mediaId) {
		this.markedPaperMediaId = mediaId;
	}

}
