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
 * A recipient's own broadcast consent for one channel (Wave 11, V67,
 * PAR-22-03). No row means the channel default ({@link #defaultFor}): email
 * and SMS opted in, WhatsApp opted out (explicit opt-in required).
 * Transactional messages (payments, password links) ignore these.
 */
@Entity
@Table(name = "notification_channel_preference")
public class NotificationChannelPreference extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "channel", nullable = false, updatable = false, length = 10)
	private DeliveryChannel channel;

	@Column(name = "opted_in", nullable = false)
	private boolean optedIn;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected NotificationChannelPreference() {
	}

	public NotificationChannelPreference(UUID tenantId, UUID userId, DeliveryChannel channel, boolean optedIn,
			Instant now) {
		if (channel == DeliveryChannel.IN_APP) {
			throw new IllegalArgumentException("In-app notifications have no preference");
		}
		this.tenantId = tenantId;
		this.userId = userId;
		this.channel = channel;
		this.optedIn = optedIn;
		this.updatedAt = now;
	}

	public static boolean defaultFor(DeliveryChannel channel) {
		return channel != DeliveryChannel.WHATSAPP;
	}

	public void setOptedIn(boolean optedIn, Instant now) {
		this.optedIn = optedIn;
		this.updatedAt = now;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getUserId() {
		return userId;
	}

	public DeliveryChannel getChannel() {
		return channel;
	}

	public boolean isOptedIn() {
		return optedIn;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
