package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Shared create/edit request shape for a course module. KLASS-T4a:
 * {@code sequence} is optional (appended on create, unchanged on edit);
 * {@code price} is the optional MODULE-offer price ({@code > 0}) and is
 * replaced on edit ({@code null} clears it).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseModuleRequest(@NotBlank @Size(max = 255) String title, @Positive Integer sequence,
		@DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 10, fraction = 2) BigDecimal price) {

	/** Pre-KLASS shape. */
	public CourseModuleRequest(String title, Integer sequence) {
		this(title, sequence, null);
	}

}
