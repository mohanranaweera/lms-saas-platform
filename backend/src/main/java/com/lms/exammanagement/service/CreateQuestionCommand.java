package com.lms.exammanagement.service;

import com.lms.exammanagement.domain.QuestionType;
import java.util.List;

/** {@code courseId} is deliberately absent - always the path parameter, server-resolved, never a field a caller sets independently (plan §12). */
public record CreateQuestionCommand(QuestionType questionType, String body, List<QuestionOptionCommand> options) {

}
