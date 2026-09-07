package com.lms.exammanagement.service;

import java.util.UUID;

/** {@code isCorrect} is deliberately absent - never included in any response reachable by a Student or a pre-submission Teacher/staff read (plan §10/§15). */
public record ExamQuestionOptionView(UUID id, String optionText) {

}
