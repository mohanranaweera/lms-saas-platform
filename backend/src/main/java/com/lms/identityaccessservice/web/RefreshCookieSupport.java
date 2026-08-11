package com.lms.identityaccessservice.web;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Builds/clears the refresh-token cookie: {@code HttpOnly}, {@code Secure},
 * {@code SameSite=Strict}, scoped to the refresh/logout path only (plan
 * §10/§21) - the raw refresh token is never returned in a JSON body.
 */
final class RefreshCookieSupport {

	private RefreshCookieSupport() {
	}

	static void set(HttpServletResponse response, String cookieName, String path, String rawRefreshToken,
			Duration maxAge) {
		ResponseCookie cookie = ResponseCookie.from(cookieName, rawRefreshToken)
			.httpOnly(true)
			.secure(true)
			.sameSite("Strict")
			.path(path)
			.maxAge(maxAge)
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	static void clear(HttpServletResponse response, String cookieName, String path) {
		ResponseCookie cookie = ResponseCookie.from(cookieName, "")
			.httpOnly(true)
			.secure(true)
			.sameSite("Strict")
			.path(path)
			.maxAge(Duration.ZERO)
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

}
