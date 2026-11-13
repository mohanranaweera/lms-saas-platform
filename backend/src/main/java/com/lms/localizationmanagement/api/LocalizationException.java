package com.lms.localizationmanagement.api;

import com.lms.common.error.ApplicationException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** I18N-01 error codes (doc section 6). {@code details} is rendered as {@code error.details}. */
public class LocalizationException extends ApplicationException {

	public static final String TRANSLATION_INVALID = "TRANSLATION_INVALID";

	public static final String TRANSLATION_DRAFT_STALE = "TRANSLATION_DRAFT_STALE";

	public static final String TRANSLATION_RELEASE_STALE = "TRANSLATION_RELEASE_STALE";

	public static final String NOTHING_TO_PUBLISH = "NOTHING_TO_PUBLISH";

	public static final String UNKNOWN_MESSAGE_KEY = "UNKNOWN_MESSAGE_KEY";

	public static final String LOCALE_NOT_SUPPORTED = "LOCALE_NOT_SUPPORTED";

	public static final String LOCALE_NOT_ENABLED = "LOCALE_NOT_ENABLED";

	public static final String PO_IMPORT_INVALID = "PO_IMPORT_INVALID";

	private final transient Map<String, Object> details;

	public LocalizationException(HttpStatus status, String code, String message) {
		this(status, code, message, null);
	}

	public LocalizationException(HttpStatus status, String code, String message, Map<String, Object> details) {
		super(status, code, message);
		this.details = details;
	}

	@Override
	public Map<String, Object> getDetails() {
		return details;
	}

	public static LocalizationException localeNotSupported(String locale) {
		return new LocalizationException(HttpStatus.BAD_REQUEST, LOCALE_NOT_SUPPORTED,
				"Locale is not supported: " + safe(locale));
	}

	public static LocalizationException localeNotEnabled(String locale) {
		return new LocalizationException(HttpStatus.BAD_REQUEST, LOCALE_NOT_ENABLED,
				"Locale is not enabled for this institute: " + safe(locale));
	}

	private static String safe(String value) {
		if (value == null) {
			return "null";
		}
		String trimmed = value.length() > 40 ? value.substring(0, 40) : value;
		return trimmed.replaceAll("[^A-Za-z0-9_-]", "?");
	}

}
