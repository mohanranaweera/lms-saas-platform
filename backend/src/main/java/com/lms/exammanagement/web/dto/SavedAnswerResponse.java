package com.lms.exammanagement.web.dto;

import java.util.UUID;

/** {@code GET /api/v1/exams/attempts/{attemptId}/answers} response element - one already-saved answer for the attempt. */
public record SavedAnswerResponse(UUID questionId, String response) {

}
