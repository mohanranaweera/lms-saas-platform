package com.lms.common.api;

/** Stable machine-readable error codes carried in {@link ApiError#code()}. */
public final class ApiErrorCodes {

	public static final String VALIDATION_ERROR = "VALIDATION_ERROR";

	public static final String NOT_FOUND = "NOT_FOUND";

	public static final String CONFLICT = "CONFLICT";

	public static final String UNAUTHENTICATED = "UNAUTHENTICATED";

	public static final String FORBIDDEN = "FORBIDDEN";

	public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

	private ApiErrorCodes() {
	}

}
