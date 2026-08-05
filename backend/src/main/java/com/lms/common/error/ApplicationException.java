package com.lms.common.error;

/**
 * Base type for application-level exceptions that {@code GlobalExceptionHandler}
 * maps to a specific HTTP status and {@link com.lms.common.api.ApiError} code.
 * Domain modules should extend one of the concrete subclasses (or add a new
 * one here) rather than throwing a raw {@link RuntimeException} for an
 * expected failure case.
 */
public abstract class ApplicationException extends RuntimeException {

	private final String errorCode;

	protected ApplicationException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public String getErrorCode() {
		return errorCode;
	}

}
