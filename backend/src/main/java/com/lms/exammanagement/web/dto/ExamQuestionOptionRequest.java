package com.lms.exammanagement.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One MCQ option on question create/edit (plan §10). {@code isCorrect} is
 * intentionally present here (unlike any response DTO) - authoring is
 * exactly where a correctness flag must be writable; it is validated
 * server-side (at least one {@code true} required for an MCQ question) by
 * {@code QuestionBankService}, never trusted as sufficient on its own.
 */
public record ExamQuestionOptionRequest(@NotBlank @Size(max = 500) String optionText, boolean isCorrect) {

}
