package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.BaseEntity;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a {@link VideoAsset}'s append-only processing history ({@code
 * video_asset_status_event}, V70). Never updated or deleted - a DB trigger
 * rejects both. {@code actorId} is {@code null} for system-driven transitions
 * (upload worker, provider status sync), which the audit log cannot record
 * because its actor trigger requires a real user (plan Q-013).
 */
@Entity
@Table(name = "video_asset_status_event")
public class VideoAssetStatusEvent extends BaseEntity implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "video_asset_id", nullable = false, updatable = false)
	private UUID videoAssetId;

	@Convert(converter = VideoAssetStatusConverter.class)
	@Column(name = "from_status", updatable = false)
	private VideoAssetStatus fromStatus;

	@Convert(converter = VideoAssetStatusConverter.class)
	@Column(name = "to_status", nullable = false, updatable = false)
	private VideoAssetStatus toStatus;

	@Column(name = "reason", updatable = false)
	private String reason;

	@Column(name = "actor_id", updatable = false)
	private UUID actorId;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	protected VideoAssetStatusEvent() {
	}

	public VideoAssetStatusEvent(UUID tenantId, UUID videoAssetId, VideoAssetStatus fromStatus,
			VideoAssetStatus toStatus, String reason, UUID actorId, Instant occurredAt) {
		this.tenantId = tenantId;
		this.videoAssetId = videoAssetId;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.reason = reason;
		this.actorId = actorId;
		this.occurredAt = occurredAt;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

	public UUID getVideoAssetId() {
		return videoAssetId;
	}

	public VideoAssetStatus getFromStatus() {
		return fromStatus;
	}

	public VideoAssetStatus getToStatus() {
		return toStatus;
	}

	public String getReason() {
		return reason;
	}

	public UUID getActorId() {
		return actorId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

}
