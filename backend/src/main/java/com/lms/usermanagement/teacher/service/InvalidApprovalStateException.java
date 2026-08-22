package com.lms.usermanagement.teacher.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when {@link TeacherService#approveTeacher} or {@link
 * TeacherService#rejectTeacher} is attempted against a {@code
 * TeacherProfile} that is not currently {@code PENDING} - the
 * one-directional, terminal approval state machine (approved MVP-007 plan
 * §4/§7) rejects any {@code APPROVED -> *} or {@code REJECTED -> *}
 * transition. Maps to {@code 409 CONFLICT} via {@code GlobalExceptionHandler}
 * 's generic {@link ApplicationException} handler - no new HTTP-status
 * mapping is added for this exception, matching {@code
 * InvalidStaffRoleException}'s precedent of reusing the existing generic
 * handler rather than adding a dedicated one.
 */
public class InvalidApprovalStateException extends ApplicationException {

	public InvalidApprovalStateException(String currentStatus) {
		super(HttpStatus.CONFLICT, ApiErrorCodes.CONFLICT,
				"Teacher approval cannot be changed from its current state (" + currentStatus + ")");
	}

}
