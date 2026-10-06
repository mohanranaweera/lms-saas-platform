package com.lms.videoaccessmanagement.api;

import java.util.UUID;

/**
 * Public cross-module projection of a {@code VideoAsset} row for content-management
 * and lesson-media integrations (VIDEO Phase 6). Exposes title, duration, status,
 * provider, and thumbnail without leaking internal storage keys or credentials.
 */
public record VideoAssetDetails(UUID id, String status, String provider, String title, Integer durationSeconds,
		String thumbnailUrl) {

}
