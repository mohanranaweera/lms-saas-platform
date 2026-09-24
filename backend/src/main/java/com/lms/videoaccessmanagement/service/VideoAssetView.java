package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import java.time.Instant;
import java.util.UUID;

public record VideoAssetView(UUID id, String originalFilename, String mimeType, Long sizeBytes,
		Integer durationSeconds, VideoAssetStatus status, UUID uploadedBy, Instant createdAt) {

}
