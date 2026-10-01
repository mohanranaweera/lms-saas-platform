package com.lms.identityaccessservice.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A server-issued device identity (V64, ADR-007 §2, Wave 10). The raw device
 * token lives only in the client cookie; {@code tokenHash} is its SHA-256.
 * {@code label}/{@code lastIp} are display/review data only, never identity.
 *
 * <p>Never deleted. {@link #revoke} records who/why and sets {@code
 * slotReusableAt} - the persisted cooldown before the slot this device held
 * can be taken by a NEW device ({@link #occupiesSlot}).
 */
@Entity
@Table(name = "device")
public class Device extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "token_hash", nullable = false, updatable = false)
	private String tokenHash;

	@Column(name = "label", nullable = false, updatable = false)
	private String label;

	@Column(name = "first_seen_at", nullable = false, updatable = false)
	private Instant firstSeenAt;

	@Column(name = "last_seen_at", nullable = false)
	private Instant lastSeenAt;

	@Column(name = "last_ip")
	private String lastIp;

	@Column(name = "status", nullable = false)
	private DeviceStatus status;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by")
	private UUID revokedBy;

	@Column(name = "revocation_source")
	private DeviceRevocationSource revocationSource;

	@Column(name = "revoke_reason")
	private String revokeReason;

	@Column(name = "slot_reusable_at")
	private Instant slotReusableAt;

	protected Device() {
	}

	public static Device register(UUID tenantId, UUID userId, String tokenHash, String label, String ip,
			Instant now) {
		Device device = new Device();
		device.tenantId = tenantId;
		device.userId = userId;
		device.tokenHash = tokenHash;
		device.label = label;
		device.firstSeenAt = now;
		device.lastSeenAt = now;
		device.lastIp = ip;
		device.status = DeviceStatus.ACTIVE;
		return device;
	}

	public void touch(Instant now, String ip) {
		this.lastSeenAt = now;
		if (ip != null) {
			this.lastIp = ip;
		}
	}

	public void revoke(UUID actorId, DeviceRevocationSource source, String reason, Instant now, Duration cooldown) {
		if (status == DeviceStatus.REVOKED) {
			throw new IllegalStateException("Device is already revoked");
		}
		this.status = DeviceStatus.REVOKED;
		this.revokedAt = now;
		this.revokedBy = actorId;
		this.revocationSource = source;
		this.revokeReason = reason;
		this.slotReusableAt = now.plus(cooldown);
	}

	public boolean isActive() {
		return status == DeviceStatus.ACTIVE;
	}

	/** Active, or revoked but still inside its reset cooldown - either way the slot is taken. */
	public boolean occupiesSlot(Instant now) {
		return isActive() || (slotReusableAt != null && slotReusableAt.isAfter(now));
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

	public String getTokenHash() {
		return tokenHash;
	}

	public String getLabel() {
		return label;
	}

	public Instant getFirstSeenAt() {
		return firstSeenAt;
	}

	public Instant getLastSeenAt() {
		return lastSeenAt;
	}

	public String getLastIp() {
		return lastIp;
	}

	public DeviceStatus getStatus() {
		return status;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public UUID getRevokedBy() {
		return revokedBy;
	}

	public DeviceRevocationSource getRevocationSource() {
		return revocationSource;
	}

	public String getRevokeReason() {
		return revokeReason;
	}

	public Instant getSlotReusableAt() {
		return slotReusableAt;
	}

}
