package com.lms.notificationmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Mapped 1:1 onto {@code notification_outbox} (V28). Deliberately extends
 * {@link BaseEntity} directly, not {@link com.lms.common.persistence.Auditable}/
 * {@link com.lms.common.persistence.TimestampedEntity} - this table has no
 * {@code updated_at} column (only {@code created_at}/{@code dispatched_at}),
 * mirroring {@code Payment}'s "map exactly what the migration defines"
 * discipline.
 *
 * <p>{@code payload} is stored as raw JSON text against the table's {@code
 * jsonb} column via Hibernate's native JSON mapping ({@link JdbcTypeCode}),
 * mirroring {@code AuditLog#metadata} exactly - callers ({@code
 * NotificationOutboxService}) are responsible for serializing a structured
 * rendering-variable map to a JSON string before construction; this entity
 * does not itself perform JSON serialization.
 *
 * <p>{@code PENDING -> SENDING -> SENT|FAILED} is the only legal path
 * (defense in depth alongside V28's {@code ck_notification_outbox_status}/
 * {@code ck_notification_outbox_dispatched_together} CHECKs, and V29's
 * {@code ck_notification_outbox_claimed_together} CHECK), enforced here
 * by {@link #markSending(Instant)}/{@link #markSent(Instant)}/{@link
 * #markFailed(Instant)}, each of which throws {@link IllegalStateException}
 * if the row is not in the required prior state. No setter exists for
 * {@code status}/{@code dispatchedAt}/{@code claimedAt} beyond these three
 * methods.
 *
 * <p>{@code claimedAt} (V29) is the reconciliation timeout's own timestamp -
 * see {@code NotificationDispatchPoller}'s stuck-{@code SENDING} row
 * reconciliation step javadoc - distinct from {@code dispatchedAt}, which
 * stays {@code null} for the entire time a row is stuck at {@code SENDING}.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutbox extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, updatable = false, length = 30)
	private NotificationEventType eventType;

	@Column(name = "recipient_user_id", nullable = false, updatable = false)
	private UUID recipientUserId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, updatable = false)
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private NotificationDispatchStatus status;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "dispatched_at")
	private Instant dispatchedAt;

	@Column(name = "claimed_at")
	private Instant claimedAt;

	/** Wave 11 (V68, decision D3): claims so far; at most {@link #MAX_ATTEMPTS}. */
	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	/** Wave 11 (V68): when a retried PENDING row becomes due again; null = due now. */
	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	/** Wave 11 (V68): identifies the business event, unique per tenant when set (duplicate-event guard). */
	@Column(name = "dedupe_key", updatable = false, length = 120)
	private String dedupeKey;

	public static final int MAX_ATTEMPTS = 3;

	protected NotificationOutbox() {
	}

	public NotificationOutbox(UUID tenantId, NotificationEventType eventType, UUID recipientUserId, String payload,
			Instant createdAt) {
		this(tenantId, eventType, recipientUserId, payload, createdAt, null);
	}

	public NotificationOutbox(UUID tenantId, NotificationEventType eventType, UUID recipientUserId, String payload,
			Instant createdAt, String dedupeKey) {
		this.tenantId = tenantId;
		this.eventType = eventType;
		this.recipientUserId = recipientUserId;
		this.payload = payload;
		this.status = NotificationDispatchStatus.PENDING;
		this.createdAt = createdAt;
		this.dispatchedAt = null;
		this.attemptCount = 0;
		this.dedupeKey = dedupeKey;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public NotificationEventType getEventType() {
		return eventType;
	}

	public UUID getRecipientUserId() {
		return recipientUserId;
	}

	public String getPayload() {
		return payload;
	}

	public NotificationDispatchStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getDispatchedAt() {
		return dispatchedAt;
	}

	public Instant getClaimedAt() {
		return claimedAt;
	}

	/**
	 * The only legal path to {@code SENDING} - claims the row for dispatch
	 * before any outbound SMTP call is made, per {@code
	 * NotificationDispatchClaimService}'s javadoc. {@code claimedAt} (V29) is
	 * the reconciliation timeout's reference point - see {@code
	 * NotificationDispatchPoller}'s stuck-{@code SENDING} reconciliation step.
	 */
	public void markSending(Instant claimedAt) {
		if (status != NotificationDispatchStatus.PENDING) {
			throw new IllegalStateException("Only a PENDING outbox row may transition to SENDING, was " + status);
		}
		this.status = NotificationDispatchStatus.SENDING;
		this.claimedAt = claimedAt;
		this.attemptCount++;
		this.nextAttemptAt = null;
	}

	/** True when a transient failure of the current attempt may still be retried (decision D3). */
	public boolean canRetry() {
		return attemptCount < MAX_ATTEMPTS;
	}

	/**
	 * Wave 11 (decision D3): a transient failure returns the row to {@code
	 * PENDING}, due again at {@code nextAttemptAt}. Clears {@code claimedAt}
	 * (V29's claimed-together CHECK requires it for a PENDING row).
	 */
	public void markRetry(Instant nextAttemptAt) {
		if (status != NotificationDispatchStatus.SENDING) {
			throw new IllegalStateException("Only a SENDING outbox row may be scheduled for retry, was " + status);
		}
		if (!canRetry()) {
			throw new IllegalStateException("Outbox row has used all " + MAX_ATTEMPTS + " attempts");
		}
		this.status = NotificationDispatchStatus.PENDING;
		this.claimedAt = null;
		this.nextAttemptAt = nextAttemptAt;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public String getDedupeKey() {
		return dedupeKey;
	}

	/** The only legal path to {@code SENT}. */
	public void markSent(Instant dispatchedAt) {
		if (status != NotificationDispatchStatus.SENDING) {
			throw new IllegalStateException("Only a SENDING outbox row may transition to SENT, was " + status);
		}
		this.status = NotificationDispatchStatus.SENT;
		this.dispatchedAt = dispatchedAt;
	}

	/** The only legal path to {@code FAILED}. Terminal - never re-claimed (no retry). */
	public void markFailed(Instant dispatchedAt) {
		if (status != NotificationDispatchStatus.SENDING) {
			throw new IllegalStateException("Only a SENDING outbox row may transition to FAILED, was " + status);
		}
		this.status = NotificationDispatchStatus.FAILED;
		this.dispatchedAt = dispatchedAt;
	}

}
