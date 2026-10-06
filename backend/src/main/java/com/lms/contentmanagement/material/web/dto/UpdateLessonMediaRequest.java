package com.lms.contentmanagement.material.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Request payload for editing a lesson's video material metadata. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpdateLessonMediaRequest(
		@NotBlank(message = "title is required") @Size(max = 255, message = "title must not exceed 255 characters") String title,
		@NotNull(message = "sequence is required") @Positive(message = "sequence must be positive") Integer sequence,
		@NotNull(message = "visibility is required") MaterialVisibility visibility) {

}
