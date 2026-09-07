package com.lms.exammanagement.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** {@code PUT /api/v1/exams/questions/{questionId}} (plan §10) - {@code questionType} is deliberately absent; a question's type is fixed at creation. */
public record ExamQuestionUpdateRequest(@NotBlank @Size(max = 20000) String body,
		List<@Valid ExamQuestionOptionRequest> options) {

}
