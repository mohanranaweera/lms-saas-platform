package com.lms.exammanagement.service;

import java.math.BigDecimal;
import java.util.UUID;

/** {@code isCorrect} is deliberately absent - a per-question result never leaks the answer key, only the student's own recorded response and score (plan §10/§15). */
public record QuestionAnswerResultView(UUID questionId, String response, BigDecimal autoScore,
		BigDecimal manualScore) {

}
