package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * An uploaded file exceeds the maximum allowed size.
 *
 * <p>{@code message} is returned to the client verbatim by {@code GlobalExceptionHandler}
 * - callers must pass only client-safe text (e.g. "The uploaded file exceeds the maximum
 * allowed size"), never an internal id, another tenant's data, or any detail that shouldn't
 * cross the trust boundary. Log sensitive detail server-side separately if needed; don't
 * put it in this exception's message.
 */
public class PayloadTooLargeException extends ApplicationException {

	public PayloadTooLargeException(String message) {
		super(HttpStatus.PAYLOAD_TOO_LARGE, ApiErrorCodes.PAYLOAD_TOO_LARGE, message);
	}

}
