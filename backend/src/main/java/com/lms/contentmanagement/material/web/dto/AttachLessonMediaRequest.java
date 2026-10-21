package com.lms.contentmanagement.material.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request payload for attaching an existing Media Library video asset to a course
 * lesson (VIDEO Phase 6, VID-ADR-003).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AttachLessonMediaRequest(
		@NotNull(message = "mediaAssetId is required") UUID mediaAssetId,
		@Size(max = 255, message = "title must not exceed 255 characters") String title,
		@Positive(message = "sequence must be positive") Integer sequence) {

}
