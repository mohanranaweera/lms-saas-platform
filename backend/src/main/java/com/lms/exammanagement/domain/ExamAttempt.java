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

	/** V113: {@code true} for a Klass exam attempt (one per student, deadline, graded once). */
	@Column(name = "klass_attempt", nullable = false, updatable = false)
	private boolean klassAttempt;

	/** Klass: {@code min(startedAt + duration, exam end)}, fixed at start. */
	@Column(name = "deadline_at", updatable = false)
	private Instant deadlineAt;

	/** Klass: computed once at finalization ({@code gradedAt}); never on retries. */
	@Column(name = "score", precision = 10, scale = 2)
	private BigDecimal score;

	@Column(name = "graded_at")
	private Instant gradedAt;

	@Column(name = "answered_count")
	private Integer answeredCount;

	@Enumerated(EnumType.STRING)
	@Column(name = "finalized_reason", length = 10)
	private ExamFinalizedReason finalizedReason;

	protected ExamAttempt() {
	}

	/** A new Klass attempt (V113): {@code IN_PROGRESS} until submitted or the deadline passes. */
	public static ExamAttempt klassAttempt(UUID tenantId, UUID examId, UUID studentId, Instant startedAt,
			Instant deadlineAt) {
		ExamAttempt attempt = new ExamAttempt(tenantId, examId, studentId, startedAt);
		attempt.klassAttempt = true;
		attempt.deadlineAt = deadlineAt;
		return attempt;
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

	public boolean isKlassAttempt() {
		return klassAttempt;
	}

	public Instant getDeadlineAt() {
		return deadlineAt;
	}

	public BigDecimal getScore() {
		return score;
	}

	public Instant getGradedAt() {
		return gradedAt;
	}

	public Integer getAnsweredCount() {
		return answeredCount;
	}

	public ExamFinalizedReason getFinalizedReason() {
		return finalizedReason;
	}

	public boolean isFinalized() {
		return status != ExamAttemptStatus.IN_PROGRESS;
	}

	/**
	 * Klass finalization (D2) - the single {@code IN_PROGRESS -> SUBMITTED|EXPIRED} transition,
	 * graded in the same step. The caller holds the row lock and has checked {@code
	 * IN_PROGRESS}; the database forbids any later change of these facts.
	 */
	public void finalizeKlass(ExamFinalizedReason reason, Instant finalizedAt, BigDecimal score, int answeredCount,
			Instant gradedAt) {
		this.status = reason == ExamFinalizedReason.SUBMITTED ? ExamAttemptStatus.SUBMITTED : ExamAttemptStatus.EXPIRED;
		this.finalizedReason = reason;
		this.submittedAt = finalizedAt;
		this.score = score;
		this.answeredCount = answeredCount;
		this.gradedAt = gradedAt;
	}

}
