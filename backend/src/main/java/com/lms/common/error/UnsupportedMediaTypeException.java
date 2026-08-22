package com.lms.common.error;

import com.lms.common.api.ApiErrorCodes;
import org.springframework.http.HttpStatus;

/**
 * An uploaded file's declared or sniffed content type is not on the accepted allow-list.
 *
 * <p>{@code message} is returned to the client verbatim by {@code GlobalExceptionHandler}
 * - callers must pass only client-safe text (e.g. "The uploaded file's content does not
 * match an accepted format"), never an internal id, another tenant's data, or any detail
 * that shouldn't cross the trust boundary. Log sensitive detail server-side separately if
 * needed; don't put it in this exception's message.
 */
public class UnsupportedMediaTypeException extends ApplicationException {

	public UnsupportedMediaTypeException(String message) {
		super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiErrorCodes.UNSUPPORTED_MEDIA_TYPE, message);
	}

}
