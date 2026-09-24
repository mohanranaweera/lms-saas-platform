package com.lms.contentmanagement.material.service;

import com.lms.contentmanagement.material.domain.MaterialType;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import java.time.Instant;
import java.util.UUID;

public record MaterialView(UUID id, UUID lessonId, UUID sessionId, MaterialType materialType, String title,
		String originalFilename, String mimeType, Long sizeBytes, String externalUrl, String noteContent,
		UUID videoAssetId, Integer sequence, MaterialVisibility visibility, Integer maxDownloads,
		Integer downloadCount, Instant availableFromAt, Instant expiryAt, UUID uploadedBy, Instant createdAt,
		Instant updatedAt) {

}
