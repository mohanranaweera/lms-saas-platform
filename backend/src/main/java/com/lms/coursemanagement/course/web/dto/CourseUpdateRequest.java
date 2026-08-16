package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Course edit request body. Deliberately excludes {@code teacherId}, {@code
 * price}, and {@code status} - those have their own dedicated endpoints
 * ({@code POST .../teacher}, {@code PATCH .../price}, {@code POST
 * .../publish}/{@code unpublish}) and are never accepted here for any
 * caller, staff included, per plan §10/§12. {@code @JsonIgnoreProperties
 * (ignoreUnknown = true)} means an unexpected {@code price}/{@code
 * teacherId}/{@code status} field in the request body is silently dropped,
 * not bound to anything - there is no field on this record for it to land
 * on.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseUpdateRequest(

		@NotBlank @Size(max = 255) String name,

		@NotBlank @Size(max = 160) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
				message = "must be lowercase letters, numbers, and single hyphens only") String slug,

		@NotBlank @Size(max = 100) String category,

		@Size(max = 100) String subject,

		@Size(max = 100) String stream,

		@Size(max = 50) String grade,

		@Size(max = 20) String academicYear,

		@Size(max = 5000) String description,

		@Size(max = 1000) String enrollmentRule,

		@Positive Integer accessDurationDays) {

}
