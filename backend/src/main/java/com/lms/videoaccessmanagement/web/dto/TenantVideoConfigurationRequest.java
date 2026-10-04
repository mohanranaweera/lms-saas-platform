package com.lms.videoaccessmanagement.web.dto;

import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.MediaPrivacyStatus;
import com.lms.videoaccessmanagement.domain.TenantVideoSettings;
import com.lms.videoaccessmanagement.domain.WatchTimePolicy;
import com.lms.videoaccessmanagement.domain.WatermarkField;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Full replacement of the tenant's video settings ({@code PUT
 * /api/v1/tenant-admin/video-configuration}). Ranges are validated by the
 * service; there is deliberately no {@code tenantId} field.
 */
public record TenantVideoConfigurationRequest(@NotNull MediaAssetProvider defaultProvider,
		@NotNull MediaPrivacyStatus defaultPrivacyStatus, @NotNull Boolean uploadsEnabled,
		@NotNull Boolean nativePlayerRequired, @NotNull Boolean watermarkEnabled, @NotNull BigDecimal watermarkOpacity,
		@NotNull Integer watermarkMovementIntervalSeconds, @NotNull Set<WatermarkField> watermarkFields,
		@NotNull Boolean captureProtectionEnabled, @NotNull Boolean captureProtectionRequired,
		@NotNull Integer maxConcurrentSessions, @NotNull WatchTimePolicy watchTimePolicy,
		BigDecimal watchTimeMultiplier, Integer watchTimeFixedMinutes, @NotNull Integer launchCodeTtlSeconds,
		@NotNull Integer heartbeatIntervalSeconds) {

	public TenantVideoSettings toSettings() {
		return new TenantVideoSettings(defaultProvider, defaultPrivacyStatus, uploadsEnabled, nativePlayerRequired,
				watermarkEnabled, watermarkOpacity, watermarkMovementIntervalSeconds, watermarkFields,
				captureProtectionEnabled, captureProtectionRequired, maxConcurrentSessions, watchTimePolicy,
				watchTimeMultiplier, watchTimeFixedMinutes, launchCodeTtlSeconds, heartbeatIntervalSeconds);
	}

}
