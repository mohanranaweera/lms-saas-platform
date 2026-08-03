package com.lms.common.api;

import java.util.List;

/**
 * Machine-readable error payload carried inside {@link ApiResponse} when
 * {@code success} is false. {@code fieldErrors} is populated only for
 * validation failures; it is empty (never null) otherwise.
 */
public record ApiError(String code, String message, List<FieldError> fieldErrors) {

	public ApiError {
		fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
	}

	public static ApiError of(String code, String message) {
		return new ApiError(code, message, List.of());
	}

	public static ApiError validation(String message, List<FieldError> fieldErrors) {
		return new ApiError(ApiErrorCodes.VALIDATION_ERROR, message, fieldErrors);
	}

}
