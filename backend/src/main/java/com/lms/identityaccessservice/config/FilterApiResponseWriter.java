package com.lms.identityaccessservice.config;

import com.lms.common.api.ApiError;
import com.lms.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the standard {@link ApiResponse} envelope directly to a servlet
 * response from code that runs outside Spring MVC dispatch (filters,
 * {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler}) - {@code
 * GlobalExceptionHandler}'s {@code @RestControllerAdvice} only intercepts
 * exceptions thrown during controller execution, never ones raised earlier
 * in the filter chain.
 *
 * <p>Uses {@code tools.jackson.databind.ObjectMapper} (Jackson 3), not
 * {@code com.fasterxml.jackson.databind.ObjectMapper} (Jackson 2) - Spring
 * Boot 4.1's auto-configured {@code ObjectMapper} bean is the former; the
 * latter type is only on the classpath transitively (via {@code
 * jjwt-jackson}) and has no bean registered for it.
 */
final class FilterApiResponseWriter {

	private FilterApiResponseWriter() {
	}

	static void write(HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status, String code,
			String message) throws IOException {
		if (response.isCommitted()) {
			return;
		}
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(ApiError.of(code, message))));
	}

}
