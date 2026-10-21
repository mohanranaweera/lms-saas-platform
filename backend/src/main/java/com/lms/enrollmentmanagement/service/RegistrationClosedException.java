package com.lms.enrollmentmanagement.service;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * 409 {@code REGISTRATION_CLOSED} (D15): the course is visible but no longer
 * accepts NEW participants. Never thrown for a student who already
 * participates - closing registration never removes participation or access.
 */
public class RegistrationClosedException extends ApplicationException {

	public static final String CODE = "REGISTRATION_CLOSED";

	public RegistrationClosedException() {
		super(HttpStatus.CONFLICT, CODE, "Registration for this course is closed.");
	}

}
