package com.lms.enrollmentmanagement.service;

import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * 409 {@code SELF_ENROLLMENT_DISABLED} (SET-CFG-01): the tenant switched
 * student self-enrollment off, so only staff may add new participants. Never
 * thrown for a student who already participates.
 */
public class SelfEnrollmentDisabledException extends ApplicationException {

	public static final String CODE = "SELF_ENROLLMENT_DISABLED";

	public SelfEnrollmentDisabledException() {
		super(HttpStatus.CONFLICT, CODE, "Self-enrollment is disabled. Please contact the institute to enroll.");
	}

}
