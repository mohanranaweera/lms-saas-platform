package com.lms.exammanagement.web.dto;

import com.lms.exammanagement.domain.ExamAttemptStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** {@code { attemptId, examId, status, score, maxScore, answers: [...] } } per plan §10 - returned only once {@code results_published_at IS NOT NULL} (see {@code ExamResultsResponse}). */
public record ExamAttemptResultResponse(UUID attemptId, UUID examId, ExamAttemptStatus status, BigDecimal score,
		BigDecimal maxScore, List<QuestionAnswerResultResponse> answers) {

}
