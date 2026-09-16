package com.lms.identityaccessservice.web;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * Builds/clears the refresh-token cookie: {@code HttpOnly}, {@code Secure},
 * {@code SameSite=Strict}, scoped to the refresh/logout path only (plan
 * §10/§21) - the raw refresh token is never returned in a JSON body.
 *
 * <p>{@code secure} is caller-supplied rather than hardcoded {@code true} so
 * that {@code AuthController}/{@code PlatformAdminAuthController} can pass
 * {@code false} under the {@code local} Spring profile only - a browser
 * refuses to store a {@code Secure} cookie at all over plain {@code http://},
 * which otherwise silently breaks refresh/session-persistence for local dev
 * (frontend and backend both served over HTTP). Every other profile
 * (test/staging/production) still gets {@code true}, unchanged.
 */
final class RefreshCookieSupport {

	private RefreshCookieSupport() {
	}

	static void set(HttpServletResponse response, String cookieName, String path, String rawRefreshToken,
			Duration maxAge, boolean secure) {
		ResponseCookie cookie = ResponseCookie.from(cookieName, rawRefreshToken)
			.httpOnly(true)
			.secure(secure)
			.sameSite("Strict")
			.path(path)
			.maxAge(maxAge)
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	static void clear(HttpServletResponse response, String cookieName, String path, boolean secure) {
		ResponseCookie cookie = ResponseCookie.from(cookieName, "")
			.httpOnly(true)
			.secure(secure)
			.sameSite("Strict")
			.path(path)
			.maxAge(Duration.ZERO)
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

}
