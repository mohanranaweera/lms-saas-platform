package com.lms.common.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * STORE-01 / ADR-037: a storefront rule refused the request (slug rules,
 * product assignment, out-of-scope course). Carries one of the {@code
 * STOREFRONT_*} codes from {@link com.lms.common.api.ApiErrorCodes} and,
 * optionally, safe-to-expose details.
 */
public class StorefrontException extends ApplicationException {

	private final Map<String, Object> details;

	public StorefrontException(HttpStatus status, String errorCode, String message) {
		this(status, errorCode, message, null);
	}

	public StorefrontException(HttpStatus status, String errorCode, String message, Map<String, Object> details) {
		super(status, errorCode, message);
		this.details = (details == null) ? null : Map.copyOf(details);
	}

	@Override
	public Map<String, Object> getDetails() {
		return details;
	}

}
