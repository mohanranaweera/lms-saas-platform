package com.lms.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable error payload carried inside {@link ApiResponse} when
 * {@code success} is false. {@code fieldErrors} is populated only for
 * validation failures; it is empty (never null) otherwise. {@code details}
 * is an optional, error-specific machine-readable payload (e.g. the fresh
 * quote of a {@code 409 QUOTE_CHANGED}); omitted from the JSON when absent.
 */
public record ApiError(String code, String message, List<FieldError> fieldErrors,
		@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Object> details) {

	public ApiError {
		fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
	}

	public ApiError(String code, String message, List<FieldError> fieldErrors) {
		this(code, message, fieldErrors, null);
	}

	public static ApiError of(String code, String message) {
		return new ApiError(code, message, List.of());
	}

	public static ApiError validation(String message, List<FieldError> fieldErrors) {
		return new ApiError(ApiErrorCodes.VALIDATION_ERROR, message, fieldErrors);
	}

}
