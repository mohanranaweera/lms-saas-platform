package com.lms.coursemanagement.course.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a candidate {@code teacherId} supplied by a staff caller (on
 * course creation or the dedicated teacher-reassignment endpoint) does not
 * resolve to a {@code tenant_user} row in the caller's own tenant with
 * {@code role = TEACHER}. A clear {@code 400}, not the raw {@code
 * DataIntegrityViolationException} the composite {@code fk_course_teacher}
 * constraint would otherwise surface (that constraint remains the schema
 * -level last line of defense - see V11 - not the primary validation path;
 * see {@code CourseService#validateTeacherCandidate}).
 */
public class InvalidTeacherAssignmentException extends ApplicationException {

	public InvalidTeacherAssignmentException(String message) {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR, message);
	}

}
