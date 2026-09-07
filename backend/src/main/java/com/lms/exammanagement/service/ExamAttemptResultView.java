package com.lms.exammanagement.service;

import com.lms.exammanagement.domain.ExamAttemptStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ExamAttemptResultView(UUID attemptId, UUID examId, ExamAttemptStatus status, BigDecimal score,
		BigDecimal maxScore, List<QuestionAnswerResultView> answers) {

}
