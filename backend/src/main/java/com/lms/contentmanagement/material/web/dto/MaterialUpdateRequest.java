package com.lms.contentmanagement.material.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Full-resource edit request: title, position, and visibility - upload fields (file, mime type, storage key) are immutable after creation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MaterialUpdateRequest(@NotBlank @Size(max = 255) String title, @NotNull @Positive Integer sequence,
		@NotNull MaterialVisibility visibility) {

}
