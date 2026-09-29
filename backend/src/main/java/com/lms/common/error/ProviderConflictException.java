package com.lms.common.error;

import org.springframework.http.HttpStatus;

/**
 * A {@code 409} carrying a specific provider-integration code (MEDIA-INT-01,
 * e.g. {@code PROVIDER_NOT_CONNECTED}) instead of the generic {@code
 * CONFLICT}, so the client can show the right call to action ("connect your
 * Zoom account", "a licensed Zoom account is required").
 *
 * <p>As with {@link ConflictException}, {@code message} is returned verbatim
 * and must be client-safe.
 */
public class ProviderConflictException extends ApplicationException {

	public ProviderConflictException(String errorCode, String message) {
		super(HttpStatus.CONFLICT, errorCode, message);
	}

}
