package com.lms.exammanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * {@code exam_notification_dispatch} (V115): one accepted "Notify Marks" send of a Klass exam
 * (contract "Slice E"). The {@code Idempotency-Key} is unique per exam; a replay returns this
 * row. Append-only (DB trigger).
 */
@Entity
@Immutable
@Table(name = "exam_notification_dispatch")
public class ExamNotificationDispatch extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "exam_id", nullable = false, updatable = false)
	private UUID examId;

	@Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
	private String idempotencyKey;

	@Column(name = "recipient_count", nullable = false, updatable = false)
	private int recipientCount;

	@Column(name = "without_marks_count", nullable = false, updatable = false)
	private int withoutMarksCount;

	@Column(name = "resend", nullable = false, updatable = false)
	private boolean resend;

	@Column(name = "sent_by", nullable = false, updatable = false)
	private UUID sentBy;

	@Column(name = "sent_at", nullable = false, updatable = false)
	private Instant sentAt;

	protected ExamNotificationDispatch() {
	}

	public ExamNotificationDispatch(UUID tenantId, UUID examId, String idempotencyKey, int recipientCount,
			int withoutMarksCount, boolean resend, UUID sentBy, Instant sentAt) {
		this.tenantId = tenantId;
		this.examId = examId;
		this.idempotencyKey = idempotencyKey;
		this.recipientCount = recipientCount;
		this.withoutMarksCount = withoutMarksCount;
		this.resend = resend;
		this.sentBy = sentBy;
		this.sentAt = sentAt;
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

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public int getRecipientCount() {
		return recipientCount;
	}

	public int getWithoutMarksCount() {
		return withoutMarksCount;
	}

	public boolean isResend() {
		return resend;
	}

	public UUID getSentBy() {
		return sentBy;
	}

	public Instant getSentAt() {
		return sentAt;
	}

}
