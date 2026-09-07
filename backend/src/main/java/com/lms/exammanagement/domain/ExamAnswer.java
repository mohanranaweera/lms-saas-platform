package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code exam_answer} (V26). {@code attemptId}/{@code
 * questionId}/{@code examId}/{@code markedBy} are OPAQUE structural/
 * cross-domain ids only - never a JPA association.
 *
 * <p>{@code examId} is a documented, ACCEPTED denormalization from {@code
 * attemptId -> exam_attempt.exam_id} (mirrors {@code
 * attendance_record.course_id}'s V25 precedent exactly) - it exists solely to
 * back the marking-queue's {@code (tenant_id, exam_id)} read. {@code
 * ExamAttemptService} MUST derive this constructor argument server-side from
 * the attempt's real parent exam - never from a client-supplied {@code
 * examId} field on any request DTO (no such field is ever bound; see {@code
 * SaveAnswerRequest}).
 */
@Entity
@Table(name = "exam_answer")
public class ExamAnswer extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "attempt_id", nullable = false, updatable = false)
	private UUID attemptId;

	@Column(name = "question_id", nullable = false, updatable = false)
	private UUID questionId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "response")
	private String response;

	@Column(name = "auto_score", precision = 6, scale = 2)
	private BigDecimal autoScore;

	@Column(name = "manual_score", precision = 6, scale = 2)
	private BigDecimal manualScore;

	@Column(name = "marked_by")
	private UUID markedBy;

	@Column(name = "marked_at")
	private Instant markedAt;

	protected ExamAnswer() {
	}

	public ExamAnswer(UUID tenantId, UUID attemptId, UUID questionId, UUID examId, String response) {
		this.tenantId = tenantId;
		this.attemptId = attemptId;
		this.questionId = questionId;
		this.examId = examId;
		this.response = response;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getAttemptId() {
		return attemptId;
	}

	public UUID getQuestionId() {
		return questionId;
	}

	public UUID getExamId() {
		return examId;
	}

	public String getResponse() {
		return response;
	}

	/** Overwrites a mid-attempt answer-save (plan §10 {@code PUT .../answers}) - legal only before submission; the caller enforces that, not this setter. */
	public void setResponse(String response) {
		this.response = response;
	}

	public BigDecimal getAutoScore() {
		return autoScore;
	}

	/** Written ONLY by {@code McqAutoMarkingService}, computed from {@code exam_question_option.is_correct} - never from a client-supplied value. */
	public void setAutoScore(BigDecimal autoScore) {
		this.autoScore = autoScore;
	}

	public BigDecimal getManualScore() {
		return manualScore;
	}

	public UUID getMarkedBy() {
		return markedBy;
	}

	public Instant getMarkedAt() {
		return markedAt;
	}

	/**
	 * Records a manual mark - {@code manualScore}/{@code markedBy}/{@code
	 * markedAt} are always set together (mirrors {@code
	 * ck_exam_answer_marked_fields_together}, V26), {@code markedBy} always
	 * server-derived from the authenticated marker, never the request body.
	 */
	public void recordManualMark(BigDecimal manualScore, UUID markedBy, Instant markedAt) {
		this.manualScore = manualScore;
		this.markedBy = markedBy;
		this.markedAt = markedAt;
	}

}
