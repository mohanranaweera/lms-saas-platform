package com.lms.localizationmanagement.api;

import java.util.Map;

/**
 * Server-side message lookup (emails and other backend-rendered text) for the current
 * {@code TenantContext} tenant, with the doc section 4 fallback (published tenant override ->
 * reviewed platform translation -> English source). Output is plain text; the caller escapes
 * it (or its parameters) for HTML.
 */
public interface Translator {

	/** Formats the message {@code key} in {@code locale}; an unregistered key throws. */
	String msg(String key, String locale, Map<String, ?> params);

}
