package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * {@code exam_attempt_item} (V113): one frozen paper item of a Klass MCQ attempt, at its
 * per-attempt position ({@code sequence}) with its per-attempt option order - both fixed once
 * at start. Answers are written only by the conditional, revision-guarded update in {@code
 * ExamAttemptItemRepository} under the attempt's row lock; grading writes {@code
 * awardedMarks} once. Holds no answer key itself.
 */
@Entity
@Table(name = "exam_attempt_item")
public class ExamAttemptItem extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "attempt_id", nullable = false, updatable = false)
	private UUID attemptId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "sequence", nullable = false, updatable = false)
	private int sequence;

	@Column(name = "paper_item_id", nullable = false, updatable = false)
	private UUID paperItemId;

	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "option_order", nullable = false, updatable = false, columnDefinition = "uuid[]")
	private UUID[] optionOrder;

	@Column(name = "selected_option_id", insertable = false, updatable = false)
	private UUID selectedOptionId;

	@Column(name = "revision", nullable = false, insertable = false, updatable = false)
	private long revision;

	@Column(name = "answered_at", insertable = false, updatable = false)
	private Instant answeredAt;

	@Column(name = "awarded_marks", insertable = false, updatable = false, precision = 6, scale = 2)
	private BigDecimal awardedMarks;

	protected ExamAttemptItem() {
	}

	public ExamAttemptItem(UUID tenantId, UUID attemptId, UUID examId, int sequence, UUID paperItemId,
			List<UUID> optionOrder) {
		this.tenantId = tenantId;
		this.attemptId = attemptId;
		this.examId = examId;
		this.sequence = sequence;
		this.paperItemId = paperItemId;
		this.optionOrder = optionOrder.toArray(UUID[]::new);
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

	public UUID getExamId() {
		return examId;
	}

	public int getSequence() {
		return sequence;
	}

	public UUID getPaperItemId() {
		return paperItemId;
	}

	public List<UUID> getOptionOrder() {
		return List.of(optionOrder);
	}

	public UUID getSelectedOptionId() {
		return selectedOptionId;
	}

	public long getRevision() {
		return revision;
	}

	public Instant getAnsweredAt() {
		return answeredAt;
	}

	public BigDecimal getAwardedMarks() {
		return awardedMarks;
	}

}
