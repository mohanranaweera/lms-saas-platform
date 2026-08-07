package com.lms.common.error;

import org.springframework.http.HttpStatus;

/**
 * Base type for application-level exceptions that {@code GlobalExceptionHandler}
 * maps to a specific HTTP status and {@link com.lms.common.api.ApiError} code.
 * Domain modules should extend one of the concrete subclasses (or add a new
 * one here) rather than throwing a raw {@link RuntimeException} for an
 * expected failure case.
 *
 * <p>{@code httpStatus} lets {@code GlobalExceptionHandler} map any subclass
 * generically without importing domain-specific exception types (deliberately
 * kept in {@code com.lms.common} - the shared kernel must never depend back on
 * a business/domain module, per .claude/rules/architecture.md). A subclass may
 * still be given its own dedicated {@code @ExceptionHandler} in
 * {@code GlobalExceptionHandler} when it needs status-code-independent
 * handling (e.g. field errors), which Spring resolves in preference to the
 * generic {@code ApplicationException} handler by type specificity.
 */
public abstract class ApplicationException extends RuntimeException {

	private final String errorCode;

	private final HttpStatus httpStatus;

	protected ApplicationException(HttpStatus httpStatus, String errorCode, String message) {
		super(message);
		this.httpStatus = httpStatus;
		this.errorCode = errorCode;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

}
