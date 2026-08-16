package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.lms.coursemanagement.course.domain.CourseStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Course creation request body (MVP-008). {@code teacherId} is only ever
 * honored for a staff-role caller - a Teacher-role caller's own id is always
 * used server-side regardless of what (if anything) is submitted here, per
 * plan §12/§15 - see {@code CourseService#createCourse}.
 *
 * <p>{@code status}, if omitted, defaults to {@code DRAFT} server-side (V11's
 * own column default) - never silently defaults to {@code PUBLIC}, per plan
 * §12.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseCreateRequest(

		@NotBlank @Size(max = 255) String name,

		@NotBlank @Size(max = 160) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
				message = "must be lowercase letters, numbers, and single hyphens only") String slug,

		@NotBlank @Size(max = 100) String category,

		@Size(max = 100) String subject,

		@Size(max = 100) String stream,

		@Size(max = 50) String grade,

		@Size(max = 20) String academicYear,

		@Size(max = 5000) String description,

		@NotNull @DecimalMin(value = "0.0", inclusive = true) @Digits(integer = 10, fraction = 2) BigDecimal price,

		@Positive Integer accessDurationDays,

		@Size(max = 1000) String enrollmentRule,

		CourseStatus status,

		UUID teacherId) {

}
