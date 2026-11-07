package com.lms.common.error;

import org.springframework.http.HttpStatus;

/**
 * A request refused with a specific status and machine-readable error code (e.g.
 * {@code 403 IMPERSONATION_NOT_ALLOWED}, {@code 409 STUDENT_NOT_ACTIVE}).
 */
public class RequestRefusedException extends ApplicationException {

	public RequestRefusedException(HttpStatus status, String errorCode, String message) {
		super(status, errorCode, message);
	}

}
