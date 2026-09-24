package com.lms.videoaccessmanagement.api;

import java.util.UUID;

/**
 * The narrow, read-only cross-module projection of a {@code video_asset} row
 * {@code content-management} is permitted to depend on (via {@link
 * VideoAccessApi#getSummary}) - never the {@code VideoAsset} entity itself,
 * per {@code .claude/rules/architecture.md}.
 */
public record VideoAssetSummary(UUID id, String status, Integer durationSeconds) {

}
