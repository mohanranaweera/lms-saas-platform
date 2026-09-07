package com.lms.exammanagement.service;

/** One MCQ option submitted on question create/update - never carries a bindable {@code isCorrect}-only trust concern since this is server-internal, but the field IS present here (unlike any response DTO) because authoring is exactly where a correctness flag must be writable. */
public record QuestionOptionCommand(String optionText, boolean isCorrect) {

}
