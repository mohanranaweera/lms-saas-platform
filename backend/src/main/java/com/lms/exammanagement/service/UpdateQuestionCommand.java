package com.lms.exammanagement.service;

import java.util.List;

/**
 * {@code questionType} is deliberately absent - a question's type is fixed at
 * creation and never changed by {@code QuestionBankService#updateQuestion}
 * (see {@code ExamQuestion}'s own javadoc). {@code options} is ignored
 * entirely for a STRUCTURED question and required (non-empty, at least one
 * {@code isCorrect}) for an MCQ question.
 */
public record UpdateQuestionCommand(String body, List<QuestionOptionCommand> options) {

}
