package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * The request conflicts with the current state of the resource (e.g. a duplicate).
 *
 * <p>{@code message} is returned to the client verbatim by {@code GlobalExceptionHandler}
 * - callers must pass only client-safe text (e.g. "A course with this slug already
 * exists"), never an internal id, another tenant's data, or any detail that shouldn't
 * cross the trust boundary. Log sensitive detail server-side separately if needed; don't
 * put it in this exception's message.
 */
public class ConflictException extends ApplicationException {

	public ConflictException(String message) {
		super(HttpStatus.CONFLICT, ApiErrorCodes.CONFLICT, message);
	}

}
