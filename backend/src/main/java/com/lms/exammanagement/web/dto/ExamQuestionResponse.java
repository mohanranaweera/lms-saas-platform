package com.lms.exammanagement.web.dto;

import com.lms.exammanagement.domain.QuestionType;
import java.util.List;
import java.util.UUID;

/**
 * {@code { id, courseId, questionType, body, options?: [{ id, optionText }] } }
 * per plan §10 - {@code isCorrect} is NEVER included, for any caller, in any
 * read path (only {@code McqAutoMarkingService} reads it internally, via the
 * repository, never via a response DTO).
 */
public record ExamQuestionResponse(UUID id, UUID courseId, QuestionType questionType, String body,
		List<ExamQuestionOptionResponse> options) {

}
