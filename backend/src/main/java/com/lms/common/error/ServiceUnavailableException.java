package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * A dependency the request needs (e.g. object storage) is not configured/reachable.
 *
 * <p>{@code message} is returned to the client verbatim by {@code GlobalExceptionHandler}
 * - callers must pass only client-safe text (e.g. "Material upload is not available yet"),
 * never an internal id, another tenant's data, or any detail that shouldn't cross the
 * trust boundary. Log sensitive detail server-side separately if needed; don't put it in
 * this exception's message.
 */
public class ServiceUnavailableException extends ApplicationException {

	public ServiceUnavailableException(String message) {
		super(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCodes.SERVICE_UNAVAILABLE, message);
	}

}
