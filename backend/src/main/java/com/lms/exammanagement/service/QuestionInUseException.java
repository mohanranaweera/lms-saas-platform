package com.lms.exammanagement.service;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link QuestionBankService#updateQuestion} when an MCQ question's
 * options would be replaced (delete-all-recreate-with-new-ids) after the
 * question is already linked to a non-{@code DRAFT} exam ({@code
 * exam_question_link}) or already has an {@code exam_answer} row referencing
 * it (MVP-017 plan §22 addendum item 4) - a distinct {@code 409} reason code
 * ({@value #ERROR_CODE}), mirroring {@link ExamNotYetOpenException}/{@link
 * ExamWindowClosedException}'s pattern of a dedicated, frontend-distinguishable
 * error code rather than the generic {@link com.lms.common.error.ConflictException}.
 *
 * <p>{@code exam_answer.response} stores a student's selected option ids as
 * free text with no FK (by design - see {@code ExamAnswer}'s own javadoc), so
 * replacing a question's options with fresh ids after an answer/non-draft
 * link exists would silently orphan the stored response and corrupt {@code
 * McqAutoMarkingService}'s exact-set-match auto-marking with no error raised
 * anywhere - this exception closes that hole at the write path instead.
 */
public class QuestionInUseException extends ApplicationException {

	public static final String ERROR_CODE = "QUESTION_IN_USE";

	public QuestionInUseException(String message) {
		super(HttpStatus.CONFLICT, ERROR_CODE, message);
	}

}
