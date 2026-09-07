package com.lms.exammanagement.web.dto;

import java.util.UUID;

/** {@code isCorrect} is deliberately absent - never included in any response reachable by a Student or a pre-submission Teacher/staff read (plan §10/§15). */
public record ExamQuestionOptionResponse(UUID id, String optionText) {

}
