package com.lms.coursemanagement.course.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.ApplicationException;
import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link BillingConfigurationService#createOrUpdateConfiguration}
 * when the supplied fields don't match the owning course's current {@code
 * pricing_model} (V37) - e.g. a non-null {@code sessionRate} for a course
 * that is not {@code SESSION}-priced, or {@code requiresManualQuote = true}
 * for a course that is not {@code CUSTOM}-priced. A clear {@code 400},
 * mirroring {@link InvalidTeacherAssignmentException}'s exact shape for a
 * different field-mismatch case.
 */
public class InvalidBillingConfigurationException extends ApplicationException {

	public InvalidBillingConfigurationException(String message) {
		super(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR, message);
	}

}
