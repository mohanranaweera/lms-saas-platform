package com.lms.auditlogmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown when an {@link AuditLogSearchCriteria}'s {@code from} filter is
 * after its {@code to} filter. Mirrors {@code
 * attendancemanagement.service.InvalidDateRangeException}'s exact pattern -
 * a clear {@code 400 VALIDATION_ERROR}, not a raw constraint failure.
 */
public class InvalidAuditLogSearchException extends ApplicationException {

	public InvalidAuditLogSearchException(String message) {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR, message);
	}

}
