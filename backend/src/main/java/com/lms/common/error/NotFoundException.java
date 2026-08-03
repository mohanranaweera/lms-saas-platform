package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;

/**
 * A requested resource does not exist, or does not exist for the caller's tenant.
 *
 * <p>{@code message} is returned to the client verbatim by {@code GlobalExceptionHandler}
 * - callers must pass only client-safe text (e.g. "Course not found"), never an internal
 * id, another tenant's data, or any detail that shouldn't cross the trust boundary. Log
 * sensitive detail server-side separately if needed; don't put it in this exception's
 * message.
 */
public class NotFoundException extends ApplicationException {

	public NotFoundException(String message) {
		super(ApiErrorCodes.NOT_FOUND, message);
	}

}
