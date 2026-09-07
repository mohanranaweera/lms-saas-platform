package com.lms.exammanagement.web.dto;

import com.lms.exammanagement.domain.QuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** {@code POST /api/v1/exams/courses/{courseId}/questions} (plan §10) - {@code courseId} is deliberately absent, always the path parameter. */
public record ExamQuestionCreateRequest(@NotNull QuestionType questionType, @NotBlank @Size(max = 20000) String body,
		List<@Valid ExamQuestionOptionRequest> options) {

}
