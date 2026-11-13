package com.lms.localizationmanagement.api;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;

/**
 * The untrusted, per-request locale hints (doc section 2). None of them is ever persisted by
 * resolution and every one is validated against the supported/enabled locales before use.
 *
 * @param explicit {@code locale} query parameter, else the {@code X-LMS-Locale} header
 * @param cookie the {@code lms_locale} cookie (host-scoped, the host is the tenant)
 * @param acceptLanguage the raw {@code Accept-Language} header
 */
public record LocaleInputs(String explicit, String cookie, String acceptLanguage) {

	public static final String HEADER = "X-LMS-Locale";

	public static final String PARAM = "locale";

	public static final String COOKIE = "lms_locale";

	public static final LocaleInputs NONE = new LocaleInputs(null, null, null);

	public static LocaleInputs from(HttpServletRequest request) {
		String explicit = request.getParameter(PARAM);
		if (explicit == null || explicit.isBlank()) {
			explicit = request.getHeader(HEADER);
		}
		String cookie = null;
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie c : cookies) {
				if (COOKIE.equals(c.getName())) {
					cookie = c.getValue();
					break;
				}
			}
		}
		return new LocaleInputs(explicit, cookie, request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
	}

}
