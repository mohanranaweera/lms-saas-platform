package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-{@code video_asset} playback rules (Wave 5, V51, PAR-20-01..04). At
 * most one row per asset ({@code uq_video_playback_policy_asset}) - a
 * missing row means "platform default policy", applied in code by {@code
 * VideoPlaybackSessionService} (plan §4), never a nullable-everything row
 * inserted eagerly.
 */
@Entity
@Table(name = "video_playback_policy")
public class VideoPlaybackPolicy extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "video_asset_id", nullable = false, updatable = false)
	private UUID videoAssetId;

	@Column(name = "access_start_at")
	private Instant accessStartAt;

	@Column(name = "access_end_at")
	private Instant accessEndAt;

	@Column(name = "max_views_per_student")
	private Integer maxViewsPerStudent;

	@Column(name = "max_watch_duration_seconds")
	private Integer maxWatchDurationSeconds;

	@Column(name = "allow_seeking", nullable = false)
	private boolean allowSeeking;

	@Column(name = "allow_download", nullable = false)
	private boolean allowDownload;

	@Column(name = "watermark_enabled", nullable = false)
	private boolean watermarkEnabled;

	@Column(name = "max_concurrent_sessions", nullable = false)
	private Integer maxConcurrentSessions;

	protected VideoPlaybackPolicy() {
	}

	public VideoPlaybackPolicy(UUID tenantId, UUID videoAssetId, Instant accessStartAt, Instant accessEndAt,
			Integer maxViewsPerStudent, Integer maxWatchDurationSeconds, boolean allowSeeking, boolean allowDownload,
			boolean watermarkEnabled, Integer maxConcurrentSessions) {
		this.tenantId = tenantId;
		this.videoAssetId = videoAssetId;
		this.accessStartAt = accessStartAt;
		this.accessEndAt = accessEndAt;
		this.maxViewsPerStudent = maxViewsPerStudent;
		this.maxWatchDurationSeconds = maxWatchDurationSeconds;
		this.allowSeeking = allowSeeking;
		this.allowDownload = allowDownload;
		this.watermarkEnabled = watermarkEnabled;
		this.maxConcurrentSessions = maxConcurrentSessions == null ? 1 : maxConcurrentSessions;
	}

	/** Mutates every field in place - the caller (an {@code @Transactional} service method) relies on dirty checking. */
	public void update(Instant accessStartAt, Instant accessEndAt, Integer maxViewsPerStudent,
			Integer maxWatchDurationSeconds, boolean allowSeeking, boolean allowDownload, boolean watermarkEnabled,
			Integer maxConcurrentSessions) {
		this.accessStartAt = accessStartAt;
		this.accessEndAt = accessEndAt;
		this.maxViewsPerStudent = maxViewsPerStudent;
		this.maxWatchDurationSeconds = maxWatchDurationSeconds;
		this.allowSeeking = allowSeeking;
		this.allowDownload = allowDownload;
		this.watermarkEnabled = watermarkEnabled;
		this.maxConcurrentSessions = maxConcurrentSessions == null ? 1 : maxConcurrentSessions;
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

	public Instant getAccessStartAt() {
		return accessStartAt;
	}

	public Instant getAccessEndAt() {
		return accessEndAt;
	}

	public Integer getMaxViewsPerStudent() {
		return maxViewsPerStudent;
	}

	public Integer getMaxWatchDurationSeconds() {
		return maxWatchDurationSeconds;
	}

	public boolean isAllowSeeking() {
		return allowSeeking;
	}

	public boolean isAllowDownload() {
		return allowDownload;
	}

	public boolean isWatermarkEnabled() {
		return watermarkEnabled;
	}

	public Integer getMaxConcurrentSessions() {
		return maxConcurrentSessions;
	}

}
