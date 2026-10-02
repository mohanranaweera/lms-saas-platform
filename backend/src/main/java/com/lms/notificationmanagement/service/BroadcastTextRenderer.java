package com.lms.notificationmanagement.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Broadcast/template copy (Wave 11 §2.4): plain text with an allow-listed set
 * of {@code {{variable}}} placeholders. Unknown placeholders are rejected when
 * the copy is saved or sent, never rendered blindly. Output is plain text -
 * React escapes it in-app and email is sent as {@code text/plain}, so no HTML
 * escaping is applied here (that would corrupt SMS text).
 */
@Component
public class BroadcastTextRenderer {

	public static final Set<String> ALLOWED_VARIABLES = Set.of("name", "instituteName");

	private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

	/** @throws FieldValidationException when {@code text} uses a variable outside {@link #ALLOWED_VARIABLES} */
	public void validate(String field, String text) {
		if (text == null) {
			return;
		}
		List<FieldError> errors = new ArrayList<>();
		Matcher matcher = TOKEN.matcher(text);
		while (matcher.find()) {
			if (!ALLOWED_VARIABLES.contains(matcher.group(1))) {
				errors.add(new FieldError(field, "uses unknown variable {{" + matcher.group(1)
						+ "}} - allowed: {{name}}, {{instituteName}}"));
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid message text", errors);
		}
	}

	/**
	 * The values of the distinct placeholders in {@code text}, in order of first
	 * appearance - the positional body parameters ({@code {{1}}}, {@code {{2}}},
	 * ...) of the matching WhatsApp template (ADR-024 V4).
	 */
	public List<String> orderedValues(String text, Map<String, String> variables) {
		if (text == null) {
			return List.of();
		}
		Set<String> seen = new LinkedHashSet<>();
		Matcher matcher = TOKEN.matcher(text);
		while (matcher.find()) {
			seen.add(matcher.group(1));
		}
		List<String> values = new ArrayList<>();
		for (String name : seen) {
			String value = variables.get(name);
			values.add((value == null) ? "" : value);
		}
		return values;
	}

	public String render(String text, Map<String, String> variables) {
		if (text == null) {
			return null;
		}
		Matcher matcher = TOKEN.matcher(text);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			String value = variables.getOrDefault(matcher.group(1), "");
			matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
		}
		matcher.appendTail(out);
		return out.toString();
	}

}
