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

/**
 * One (broadcast, recipient, channel) delivery (Wave 11, V67). Lifecycle,
 * enforced here and by {@code ck_notification_delivery_lifecycle}:
 * {@code PENDING -> SENDING -> SENT | FAILED | SKIPPED}, or {@code SENDING ->
 * PENDING} for a bounded retry (decision D3). No delete path exists.
 *
 * <p>The recipient's address is never stored - only {@code
 * destinationMasked}. {@code lastErrorCode} is always a fixed safe code.
 */
@Entity
@Table(name = "notification_delivery")
public class NotificationDelivery extends BaseEntity implements TenantOwned {

	public enum Status {

		PENDING, SENDING, SENT, FAILED, SKIPPED

	}

	public static final int MAX_ATTEMPTS = 3;

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "broadcast_id", nullable = false, updatable = false)
	private UUID broadcastId;

	@Column(name = "recipient_user_id", nullable = false, updatable = false)
	private UUID recipientUserId;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, updatable = false, length = 10)
	private DeliveryChannel channel;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 10)
	private Status status;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "claimed_at")
	private Instant claimedAt;

	@Column(name = "completed_at")
	private Instant completedAt;

	@Column(name = "destination_masked", length = 80)
	private String destinationMasked;

	@Column(name = "provider_account_id")
	private UUID providerAccountId;

	@Column(name = "provider_message_id", length = 100)
	private String providerMessageId;

	@Column(name = "last_error_code", length = 40)
	private String lastErrorCode;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected NotificationDelivery() {
	}

	private NotificationDelivery(UUID tenantId, UUID broadcastId, UUID recipientUserId, DeliveryChannel channel,
			Status status, Instant now) {
		this.tenantId = tenantId;
		this.broadcastId = broadcastId;
		this.recipientUserId = recipientUserId;
		this.channel = channel;
		this.status = status;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** A delivery waiting for the async dispatcher. */
	public static NotificationDelivery pending(UUID tenantId, UUID broadcastId, UUID recipientUserId,
			DeliveryChannel channel, Instant now) {
		return new NotificationDelivery(tenantId, broadcastId, recipientUserId, channel, Status.PENDING, now);
	}

	/** An IN_APP delivery: the in-app row is written in the same transaction, so it is complete at once. */
	public static NotificationDelivery deliveredInApp(UUID tenantId, UUID broadcastId, UUID recipientUserId,
			Instant now) {
		NotificationDelivery delivery = new NotificationDelivery(tenantId, broadcastId, recipientUserId,
				DeliveryChannel.IN_APP, Status.SENT, now);
		delivery.attemptCount = 1;
		delivery.completedAt = now;
		return delivery;
	}

	public void markSending(Instant now) {
		require(Status.PENDING);
		this.status = Status.SENDING;
		this.claimedAt = now;
		this.nextAttemptAt = null;
		this.attemptCount++;
		this.updatedAt = now;
	}

	public void markSent(Instant now, String destinationMasked, UUID providerAccountId, String providerMessageId) {
		require(Status.SENDING);
		this.status = Status.SENT;
		this.completedAt = now;
		this.destinationMasked = destinationMasked;
		this.providerAccountId = providerAccountId;
		this.providerMessageId = providerMessageId;
		this.lastErrorCode = null;
		this.updatedAt = now;
	}

	/** Nothing was attempted with a provider (no address, no consent, channel unavailable). Terminal. */
	public void markSkipped(Instant now, String reasonCode, String destinationMasked) {
		require(Status.SENDING);
		this.status = Status.SKIPPED;
		this.completedAt = now;
		this.lastErrorCode = reasonCode;
		this.destinationMasked = destinationMasked;
		this.updatedAt = now;
	}

	public boolean canRetry() {
		return attemptCount < MAX_ATTEMPTS;
	}

	public void markRetry(Instant nextAttemptAt, String errorCode, String destinationMasked, UUID providerAccountId,
			Instant now) {
		require(Status.SENDING);
		if (!canRetry()) {
			throw new IllegalStateException("Delivery has used all " + MAX_ATTEMPTS + " attempts");
		}
		this.status = Status.PENDING;
		this.claimedAt = null;
		this.nextAttemptAt = nextAttemptAt;
		this.lastErrorCode = errorCode;
		this.destinationMasked = destinationMasked;
		this.providerAccountId = providerAccountId;
		this.updatedAt = now;
	}

	public void markFailed(Instant now, String errorCode, String destinationMasked, UUID providerAccountId) {
		require(Status.SENDING);
		this.status = Status.FAILED;
		this.completedAt = now;
		this.nextAttemptAt = null;
		this.lastErrorCode = errorCode;
		if (destinationMasked != null) {
			this.destinationMasked = destinationMasked;
		}
		if (providerAccountId != null) {
			this.providerAccountId = providerAccountId;
		}
		this.updatedAt = now;
	}

	private void require(Status expected) {
		if (status != expected) {
			throw new IllegalStateException("Delivery must be " + expected + " but was " + status);
		}
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getBroadcastId() {
		return broadcastId;
	}

	public UUID getRecipientUserId() {
		return recipientUserId;
	}

	public DeliveryChannel getChannel() {
		return channel;
	}

	public Status getStatus() {
		return status;
	}

	public int getAttemptCount() {
		return attemptCount;
	}

	public Instant getNextAttemptAt() {
		return nextAttemptAt;
	}

	public Instant getClaimedAt() {
		return claimedAt;
	}

	public Instant getCompletedAt() {
		return completedAt;
	}

	public String getDestinationMasked() {
		return destinationMasked;
	}

	public UUID getProviderAccountId() {
		return providerAccountId;
	}

	public String getProviderMessageId() {
		return providerMessageId;
	}

	public String getLastErrorCode() {
		return lastErrorCode;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
