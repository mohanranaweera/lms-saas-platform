package com.lms.videoaccessmanagement.web.dto;

import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import java.time.Instant;
import java.util.UUID;

/** Metadata only - never a raw storage URL/key, mirroring {@code MaterialResponse}'s discipline. */
public record VideoAssetResponse(UUID id, String originalFilename, String mimeType, Long sizeBytes,
		Integer durationSeconds, VideoAssetStatus status, UUID uploadedBy, Instant createdAt) {

}
