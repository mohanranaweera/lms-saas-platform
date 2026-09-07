package com.lms.exammanagement.service;

import java.util.UUID;

/** {@code examId} is deliberately absent - always server-derived from the attempt's real parent exam, never client-supplied (plan §8/§14). */
public record SaveAnswerCommand(UUID questionId, String response) {

}
