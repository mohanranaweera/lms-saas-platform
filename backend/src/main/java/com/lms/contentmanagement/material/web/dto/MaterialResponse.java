package com.lms.contentmanagement.material.web.dto;

import com.lms.contentmanagement.material.domain.MaterialVisibility;
import java.time.Instant;
import java.util.UUID;

/** Metadata only - never a raw storage URL or byte stream (see MaterialDownloadUrlResponse for the signed-URL fetch path). */
public record MaterialResponse(UUID id, UUID lessonId, String title, String originalFilename, String mimeType,
		Long sizeBytes, Integer sequence, MaterialVisibility visibility, Instant expiryAt, UUID uploadedBy,
		Instant createdAt, Instant updatedAt) {

}
