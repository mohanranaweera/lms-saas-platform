package com.lms.liveclassmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** {@code PATCH /api/v1/class-sessions/{id}} request body - legal only while {@code SCHEDULED}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClassSessionUpdateRequest(

		@NotBlank @Size(max = 255) String title,

		@Size(max = 5000) String description,

		@NotNull Instant scheduledStart,

		@NotNull Instant scheduledEnd) {

}
