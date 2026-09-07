package com.lms.exammanagement.service;

import com.lms.exammanagement.domain.QuestionType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExamQuestionView(UUID id, UUID courseId, QuestionType questionType, String body,
		List<ExamQuestionOptionView> options, Instant createdAt, Instant updatedAt) {

}
