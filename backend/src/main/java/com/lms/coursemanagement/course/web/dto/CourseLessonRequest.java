package com.lms.coursemanagement.course.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Shared create/edit request shape for a course lesson - "structure only". */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CourseLessonRequest(@NotBlank @Size(max = 255) String title, @NotNull @Positive Integer sequence) {

}
