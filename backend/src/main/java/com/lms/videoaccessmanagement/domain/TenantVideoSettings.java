package com.lms.videoaccessmanagement.domain;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

/**
 * The tenant-wide video settings (plan §11) as one immutable value - what a
 * {@link TenantVideoConfiguration} row stores and what callers read. {@link
 * #PLATFORM_DEFAULTS} applies to a tenant that has never saved its own.
 * {@code max_registered_devices} is deliberately absent: it stays in the
 * ADR-022 device policy.
 */
public record TenantVideoSettings(MediaAssetProvider defaultProvider, MediaPrivacyStatus defaultPrivacyStatus,
		boolean uploadsEnabled, boolean nativePlayerRequired, boolean watermarkEnabled, BigDecimal watermarkOpacity,
		int watermarkMovementIntervalSeconds, Set<WatermarkField> watermarkFields, boolean captureProtectionEnabled,
		boolean captureProtectionRequired, int maxConcurrentSessions, WatchTimePolicy watchTimePolicy,
		BigDecimal watchTimeMultiplier, Integer watchTimeFixedMinutes, int launchCodeTtlSeconds,
		int heartbeatIntervalSeconds) {

	public static final TenantVideoSettings PLATFORM_DEFAULTS = new TenantVideoSettings(MediaAssetProvider.INTERNAL,
			MediaPrivacyStatus.UNLISTED, true, false, true, new BigDecimal("0.25"), 30,
			EnumSet.of(WatermarkField.STUDENT_DISPLAY_ID, WatermarkField.TIMESTAMP), true, false, 1,
			WatchTimePolicy.NONE, null, null, 45, 20);

	public TenantVideoSettings {
		watermarkFields = (watermarkFields == null || watermarkFields.isEmpty())
				? EnumSet.noneOf(WatermarkField.class) : EnumSet.copyOf(watermarkFields);
	}

}
