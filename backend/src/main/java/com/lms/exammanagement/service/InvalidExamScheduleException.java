package com.lms.exammanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an MCQ question is saved with zero {@code isCorrect} options,
 * or when {@code ExamSchedulingService#scheduleExam} is attempted with zero
 * linked questions or an invalid window (plan §12/§13) - a clear {@code 400},
 * mirroring {@code coursemanagement.course.service.InvalidTeacherAssignmentException}'s
 * pattern.
 */
public class InvalidExamScheduleException extends ApplicationException {

	public InvalidExamScheduleException(String message) {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR, message);
	}

}
