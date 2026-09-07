package com.lms.exammanagement.service;

import java.util.UUID;

/** One already-saved answer for an attempt, backing {@link ExamAttemptService#getAttemptAnswers(UUID)}. */
public record SavedAnswerView(UUID questionId, String response) {

}
