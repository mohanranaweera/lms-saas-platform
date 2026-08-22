package com.lms.contentmanagement.material.service;

import com.lms.contentmanagement.material.domain.MaterialVisibility;
import java.time.Instant;
import java.util.UUID;

public record MaterialView(UUID id, UUID lessonId, String title, String originalFilename, String mimeType,
		Long sizeBytes, Integer sequence, MaterialVisibility visibility, Instant expiryAt, UUID uploadedBy,
		Instant createdAt, Instant updatedAt) {

}
