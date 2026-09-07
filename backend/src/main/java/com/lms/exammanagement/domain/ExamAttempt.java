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
 * Mapped 1:1 onto {@code exam_attempt} (V26). {@code examId}/{@code
 * studentId} are OPAQUE cross-domain/child ids only - never a JPA association
 * (per {@code .claude/rules/architecture.md}). At most one {@code
 * IN_PROGRESS} row per (tenant, exam, student) is enforced by {@code
 * uq_exam_attempt_tenant_exam_student_in_progress} (a partial unique index,
 * V26) - {@code ExamAttemptService} treats the resulting {@code
 * DataIntegrityViolationException} (already mapped to a clean {@code 409} by
 * {@code GlobalExceptionHandler}) as the expected outcome of a lost
 * concurrent-start race, never a raw {@code 500}.
 */
@Entity
@Table(name = "exam_attempt")
public class ExamAttempt extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private UUID studentId;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "submitted_at")
	private Instant submittedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private ExamAttemptStatus status;

	protected ExamAttempt() {
	}

	public ExamAttempt(UUID tenantId, UUID examId, UUID studentId, Instant startedAt) {
		this.tenantId = tenantId;
		this.examId = examId;
		this.studentId = studentId;
		this.startedAt = startedAt;
		this.status = ExamAttemptStatus.IN_PROGRESS;
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

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getSubmittedAt() {
		return submittedAt;
	}

	public ExamAttemptStatus getStatus() {
		return status;
	}

	/**
	 * The one legal transition this entity permits after construction -
	 * {@code IN_PROGRESS -> SUBMITTED}. Idempotency (rejecting a second
	 * submit) is enforced by the caller ({@code ExamAttemptService}) BEFORE
	 * calling this, never here - this method itself performs no guard so it
	 * stays a pure state mutator, matching {@code AttendanceRecord#remark}'s
	 * shape.
	 */
	public void markSubmitted(Instant submittedAt) {
		this.submittedAt = submittedAt;
		this.status = ExamAttemptStatus.SUBMITTED;
	}

}
