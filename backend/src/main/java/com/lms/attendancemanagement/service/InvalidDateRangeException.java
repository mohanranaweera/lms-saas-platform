package com.lms.attendancemanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a report/history read's {@code from} filter is after its
 * {@code to} filter (plan §12). Mirrors {@code
 * coursemanagement.course.service.InvalidTeacherAssignmentException}'s
 * pattern - a clear {@code 400}, not a raw constraint failure.
 */
public class InvalidDateRangeException extends ApplicationException {

	public InvalidDateRangeException(String message) {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR, message);
	}

}
