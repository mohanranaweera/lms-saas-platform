package com.lms.common.api;

import java.time.Instant;
import org.slf4j.MDC;

/**
 * Common response envelope returned by every REST endpoint on the platform.
 * Exactly one of {@code data}/{@code error} is populated, matching
 * {@code success}. {@code traceId} mirrors the request's correlation id (see
 * {@code CorrelationIdFilter}) so a client-reported error can be located in
 * server logs.
 */
public record ApiResponse<T>(boolean success, T data, ApiError error, Instant timestamp, String traceId) {

	public static <T> ApiResponse<T> success(T data) {
		return new ApiResponse<>(true, data, null, Instant.now(), currentTraceId());
	}

	public static <T> ApiResponse<T> error(ApiError error) {
		return new ApiResponse<>(false, null, error, Instant.now(), currentTraceId());
	}

	private static String currentTraceId() {
		String traceId = MDC.get("correlationId");
		return traceId != null ? traceId : "unknown";
	}

}
