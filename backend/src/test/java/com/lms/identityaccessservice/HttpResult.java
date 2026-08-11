package com.lms.identityaccessservice;

import com.lms.common.api.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Minimal {@code ResponseEntity}-shaped result for the MockMvc-backed
 * requests in {@link AuthIntegrationTestSupport}. MockMvc is used instead of
 * a real {@code TestRestTemplate} HTTP round-trip specifically because the
 * JDK's HTTP client implementations silently refuse to let a caller override
 * the {@code Host} header on the real wire (it is in both {@code
 * java.net.http.HttpClient}'s and {@code HttpURLConnection}'s restricted
 * -header list) - which defeats the entire point of these tests, since
 * {@code TenantResolutionFilter} resolves tenant identity from that header.
 * MockMvc dispatches directly into the real Spring Security filter chain
 * (including {@code TenantResolutionFilter}/{@code JwtAuthenticationFilter})
 * against an in-process {@code MockHttpServletRequest}, which has no such
 * restriction, while still exercising the exact same filter/controller code
 * path a real HTTP request would.
 */
public record HttpResult<T>(HttpStatus statusCode, ApiResponse<T> body, HttpHeaders headers) {

	public HttpStatus getStatusCode() {
		return statusCode;
	}

	public ApiResponse<T> getBody() {
		return body;
	}

	public HttpHeaders getHeaders() {
		return headers;
	}

}
