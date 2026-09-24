package com.lms.liveclassmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /api/v1/class-sessions} request body (Wave 4 plan §4). {@code
 * teacherId} is deliberately ABSENT - always derived server-side from the
 * course's real teacher, never client-supplied. {@code lessonId} is optional
 * (nullable) and, if present, is cross-checked server-side against {@code
 * courseId} - see {@code ClassSessionSchedulingService}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClassSessionCreateRequest(

		@NotNull UUID courseId,

		UUID lessonId,

		@NotBlank @Size(max = 255) String title,

		@Size(max = 5000) String description,

		@NotNull Instant scheduledStart,

		@NotNull Instant scheduledEnd) {

}
