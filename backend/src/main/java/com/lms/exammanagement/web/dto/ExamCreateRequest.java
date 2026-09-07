package com.lms.exammanagement.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /api/v1/exams/courses/{courseId}/exams} (plan §10) - starts life as {@code DRAFT}; window/time-limit are set later via {@code PUT}. */
public record ExamCreateRequest(@NotBlank @Size(max = 255) String title) {

}
