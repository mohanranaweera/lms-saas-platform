package com.lms.contentmanagement.material.web.dto;

import com.lms.contentmanagement.material.domain.MaterialVisibility;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response projection for a lesson's attached video media (VIDEO Phase 6, VID-ADR-003).
 * Pairs the underlying {@code Material} row with safe provider-neutral video asset metadata.
 */
public record LessonMediaResponse(
		UUID id,
		UUID lessonId,
		UUID courseId,
		UUID mediaAssetId,
		String title,
		int sequence,
		MaterialVisibility visibility,
		String provider,
		Integer durationSeconds,
		String status,
		Instant createdAt,
		Instant updatedAt) {

}
