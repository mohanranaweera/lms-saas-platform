package com.lms.videoaccessmanagement.domain;

import com.lms.common.persistence.Auditable;
import com.lms.common.persistence.TenantOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * A tenant's saved video settings ({@code tenant_video_configuration}, V71 -
 * VIDEO Phase 3). At most one row per tenant; a tenant without one uses
 * {@link TenantVideoSettings#PLATFORM_DEFAULTS}. Ranges and the watch-time
 * policy shape are enforced by the V71 CHECKs as well as the service.
 */
@Entity
@Table(name = "tenant_video_configuration")
public class TenantVideoConfiguration extends Auditable implements TenantOwned {

	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(name = "default_provider", nullable = false)
	private MediaAssetProvider defaultProvider;

	@Enumerated(EnumType.STRING)
	@Column(name = "default_privacy_status", nullable = false)
	private MediaPrivacyStatus defaultPrivacyStatus;

	@Column(name = "uploads_enabled", nullable = false)
	private boolean uploadsEnabled;

	@Column(name = "native_player_required", nullable = false)
	private boolean nativePlayerRequired;

	@Column(name = "watermark_enabled", nullable = false)
	private boolean watermarkEnabled;

	@Column(name = "watermark_opacity", nullable = false, precision = 3, scale = 2)
	private BigDecimal watermarkOpacity;

	@Column(name = "watermark_movement_interval_seconds", nullable = false)
	private int watermarkMovementIntervalSeconds;

	@Convert(converter = WatermarkFieldsConverter.class)
	@Column(name = "watermark_fields", nullable = false)
	private Set<WatermarkField> watermarkFields;

	@Column(name = "capture_protection_enabled", nullable = false)
	private boolean captureProtectionEnabled;

	@Column(name = "capture_protection_required", nullable = false)
	private boolean captureProtectionRequired;

	@Column(name = "max_concurrent_sessions", nullable = false)
	private int maxConcurrentSessions;

	@Enumerated(EnumType.STRING)
	@Column(name = "watch_time_policy", nullable = false)
	private WatchTimePolicy watchTimePolicy;

	@Column(name = "watch_time_multiplier", precision = 4, scale = 2)
	private BigDecimal watchTimeMultiplier;

	@Column(name = "watch_time_fixed_minutes")
	private Integer watchTimeFixedMinutes;

	@Column(name = "launch_code_ttl_seconds", nullable = false)
	private int launchCodeTtlSeconds;

	@Column(name = "heartbeat_interval_seconds", nullable = false)
	private int heartbeatIntervalSeconds;

	/**
	 * VIDEO Phase 4 (V72, Q-014): uploads per Pacific day. Not part of {@link
	 * TenantVideoSettings} - the Phase 3 settings API does not edit it; it is
	 * changed by platform operations for now (Phase 5 may expose it).
	 */
	@Column(name = "daily_upload_limit", nullable = false)
	private int dailyUploadLimit = DEFAULT_DAILY_UPLOAD_LIMIT;

	/** The platform default (also the V72 column default and the value for a tenant without a row). */
	public static final int DEFAULT_DAILY_UPLOAD_LIMIT = 20;

	protected TenantVideoConfiguration() {
	}

	public TenantVideoConfiguration(UUID tenantId, TenantVideoSettings settings) {
		this.tenantId = tenantId;
		apply(settings);
	}

	public void apply(TenantVideoSettings s) {
		this.defaultProvider = s.defaultProvider();
		this.defaultPrivacyStatus = s.defaultPrivacyStatus();
		this.uploadsEnabled = s.uploadsEnabled();
		this.nativePlayerRequired = s.nativePlayerRequired();
		this.watermarkEnabled = s.watermarkEnabled();
		this.watermarkOpacity = s.watermarkOpacity();
		this.watermarkMovementIntervalSeconds = s.watermarkMovementIntervalSeconds();
		this.watermarkFields = s.watermarkFields();
		this.captureProtectionEnabled = s.captureProtectionEnabled();
		this.captureProtectionRequired = s.captureProtectionRequired();
		this.maxConcurrentSessions = s.maxConcurrentSessions();
		this.watchTimePolicy = s.watchTimePolicy();
		this.watchTimeMultiplier = s.watchTimeMultiplier();
		this.watchTimeFixedMinutes = s.watchTimeFixedMinutes();
		this.launchCodeTtlSeconds = s.launchCodeTtlSeconds();
		this.heartbeatIntervalSeconds = s.heartbeatIntervalSeconds();
	}

	public TenantVideoSettings toSettings() {
		return new TenantVideoSettings(defaultProvider, defaultPrivacyStatus, uploadsEnabled, nativePlayerRequired,
				watermarkEnabled, watermarkOpacity, watermarkMovementIntervalSeconds, watermarkFields,
				captureProtectionEnabled, captureProtectionRequired, maxConcurrentSessions, watchTimePolicy,
				watchTimeMultiplier, watchTimeFixedMinutes, launchCodeTtlSeconds, heartbeatIntervalSeconds);
	}

	public int getDailyUploadLimit() {
		return dailyUploadLimit;
	}

	@Override
	public UUID getTenantId() {
		return tenantId;
	}

	@Override
	public void setTenantId(UUID tenantId) {
		this.tenantId = tenantId;
	}

}
