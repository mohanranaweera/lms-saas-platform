package com.lms.notificationmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapped 1:1 onto {@code in_app_notification} (V28). Extends {@link
 * BaseEntity} directly - this table has no {@code updated_at} column (only
 * {@code created_at}/{@code read_at}), mirroring {@code NotificationOutbox}'s
 * discipline.
 *
 * <p>{@link #markRead(Instant)} is idempotent - calling it twice (e.g. a
 * double-click race on the Notification Center's mark-read control) must not
 * throw; the second call is a harmless no-op that leaves the original {@code
 * read_at} untouched.
 */
@Entity
@Table(name = "in_app_notification")
public class InAppNotification extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "recipient_user_id", nullable = false, updatable = false)
	private UUID recipientUserId;

	@Column(name = "title", nullable = false, updatable = false)
	private String title;

	@Column(name = "body", nullable = false, updatable = false)
	private String body;

	@Column(name = "read_at")
	private Instant readAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected InAppNotification() {
	}

	public InAppNotification(UUID tenantId, UUID recipientUserId, String title, String body, Instant createdAt) {
		this.tenantId = tenantId;
		this.recipientUserId = recipientUserId;
		this.title = title;
		this.body = body;
		this.readAt = null;
		this.createdAt = createdAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getRecipientUserId() {
		return recipientUserId;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public Instant getReadAt() {
		return readAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	/** Idempotent: a second call after the row is already read is a harmless no-op. */
	public void markRead(Instant readAt) {
		if (this.readAt != null) {
			return;
		}
		this.readAt = readAt;
	}

}
