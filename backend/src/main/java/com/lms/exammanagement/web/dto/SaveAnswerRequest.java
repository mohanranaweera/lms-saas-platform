package com.lms.exammanagement.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** {@code PUT /api/v1/exams/attempts/{attemptId}/answers} (plan §10) - {@code examId} is deliberately absent, always server-derived from the attempt's real parent exam (plan §8/§14). No {@code isCorrect}/{@code score}/{@code autoScore} field exists here at all (plan §12/§15). */
public record SaveAnswerRequest(@NotNull UUID questionId, @Size(max = 20000) String response) {

}
