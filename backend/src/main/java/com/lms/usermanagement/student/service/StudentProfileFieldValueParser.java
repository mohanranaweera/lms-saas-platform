package com.lms.usermanagement.student.service;

import com.lms.common.api.FieldError;
import com.lms.tenantmanagement.api.StudentProfileField;
import com.lms.usermanagement.student.domain.GuardianDetails;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side validation and normalisation of student profile field values
 * (STU-PROF-01), converting raw JSON values ({@code String}/{@code
 * Number}/{@code Boolean}/{@code Map}/{@code null}) into the typed values
 * {@code StudentProfile#writeField} stores, and back for responses.
 *
 * <p>Text is trimmed and a blank value becomes {@code null} (cleared).
 * Problems are appended to the caller's error list with the given path;
 * nothing is thrown, so a caller can collect every problem in one pass.
 * Error messages never echo the submitted value.
 */
final class StudentProfileFieldValueParser {

	private static final Pattern PHONE = Pattern.compile("^\\+?[0-9 ()\\-]{6,20}$");

	private static final LocalDate EARLIEST_BIRTHDAY = LocalDate.of(1900, 1, 1);

	private static final int MIN_YEAR = 1990;

	private static final int MAX_YEAR = 2100;

	private static final int PHONE_MAX_LENGTH = 50;

	private static final Set<String> COUNTRY_CODES = Set.of(Locale.getISOCountries());

	private StudentProfileFieldValueParser() {
	}

	/**
	 * @return the normalised value ({@code null} = clear); when a problem is
	 * found an error is added to {@code errors} and {@code null} is returned
	 */
	static Object parse(StudentProfileField field, Object raw, String path, List<FieldError> errors) {
		if (raw == null) {
			return null;
		}
		return switch (field.type()) {
			case TEXT -> text(field, raw, field.maxLength(), path, errors);
			case PHONE -> phone(field, raw, path, errors);
			case DATE -> date(field, raw, path, errors);
			case YEAR -> year(field, raw, path, errors);
			case BOOLEAN -> bool(field, raw, path, errors);
			case COUNTRY -> country(field, raw, path, errors);
			case GUARDIAN -> guardian(field, raw, path, errors);
		};
	}

	/** Whether a stored value satisfies "required" (both parts for the guardian). */
	static boolean isFilled(Object value) {
		if (value instanceof GuardianDetails guardian) {
			return guardian.isComplete();
		}
		return value != null;
	}

	/** A JSON-friendly representation of a stored value (ISO date string, number, guardian object). */
	static Object toJson(Object value) {
		if (value instanceof LocalDate date) {
			return date.toString();
		}
		if (value instanceof Short year) {
			return year.intValue();
		}
		if (value instanceof GuardianDetails guardian) {
			Map<String, Object> json = new LinkedHashMap<>();
			json.put("name", guardian.name());
			json.put("phone", guardian.phone());
			return json;
		}
		return value;
	}

	private static String text(StudentProfileField field, Object raw, int maxLength, String path,
			List<FieldError> errors) {
		if (!(raw instanceof String s)) {
			errors.add(new FieldError(path, field.label() + " must be text"));
			return null;
		}
		String trimmed = s.trim();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (trimmed.length() > maxLength) {
			errors.add(new FieldError(path, field.label() + " must be at most " + maxLength + " characters"));
			return null;
		}
		if (trimmed.chars().anyMatch(Character::isISOControl)) {
			errors.add(new FieldError(path, field.label() + " contains invalid characters"));
			return null;
		}
		return trimmed;
	}

	private static String phone(StudentProfileField field, Object raw, String path, List<FieldError> errors) {
		int before = errors.size();
		String value = text(field, raw, PHONE_MAX_LENGTH, path, errors);
		if (value == null || errors.size() > before) {
			return null;
		}
		if (!PHONE.matcher(value).matches()) {
			errors.add(new FieldError(path, field.label() + " must be a valid phone number"));
			return null;
		}
		return value;
	}

	private static LocalDate date(StudentProfileField field, Object raw, String path, List<FieldError> errors) {
		if (!(raw instanceof String s)) {
			errors.add(new FieldError(path, field.label() + " must be a date (yyyy-MM-dd)"));
			return null;
		}
		if (s.isBlank()) {
			return null;
		}
		LocalDate date;
		try {
			date = LocalDate.parse(s.trim());
		}
		catch (DateTimeParseException ex) {
			errors.add(new FieldError(path, field.label() + " must be a date (yyyy-MM-dd)"));
			return null;
		}
		if (date.isAfter(LocalDate.now()) || date.isBefore(EARLIEST_BIRTHDAY)) {
			errors.add(new FieldError(path, field.label() + " must be a past date after 1900"));
			return null;
		}
		return date;
	}

	private static Short year(StudentProfileField field, Object raw, String path, List<FieldError> errors) {
		Integer year = null;
		if (raw instanceof Integer || raw instanceof Long || raw instanceof Short) {
			long value = ((Number) raw).longValue();
			year = (value >= MIN_YEAR && value <= MAX_YEAR) ? (int) value : null;
		}
		else if (raw instanceof String s) {
			if (s.isBlank()) {
				return null;
			}
			try {
				int value = Integer.parseInt(s.trim());
				year = (value >= MIN_YEAR && value <= MAX_YEAR) ? value : null;
			}
			catch (NumberFormatException ex) {
				year = null;
			}
		}
		if (year == null) {
			errors.add(
					new FieldError(path, field.label() + " must be a year between " + MIN_YEAR + " and " + MAX_YEAR));
			return null;
		}
		return year.shortValue();
	}

	private static Boolean bool(StudentProfileField field, Object raw, String path, List<FieldError> errors) {
		if (!(raw instanceof Boolean b)) {
			errors.add(new FieldError(path, field.label() + " must be true or false"));
			return null;
		}
		return b;
	}

	private static String country(StudentProfileField field, Object raw, String path, List<FieldError> errors) {
		if (!(raw instanceof String s)) {
			errors.add(new FieldError(path, field.label() + " must be a country code"));
			return null;
		}
		String code = s.trim().toUpperCase(Locale.ROOT);
		if (code.isEmpty()) {
			return null;
		}
		if (!COUNTRY_CODES.contains(code)) {
			errors.add(new FieldError(path, field.label() + " must be a valid ISO country code"));
			return null;
		}
		return code;
	}

	private static GuardianDetails guardian(StudentProfileField field, Object raw, String path,
			List<FieldError> errors) {
		if (!(raw instanceof Map<?, ?> map)) {
			errors.add(new FieldError(path, field.label() + " must have a name and a phone"));
			return null;
		}
		Object rawName = map.get("name");
		Object rawPhone = map.get("phone");
		String name = (rawName == null) ? null : text(field, rawName, field.maxLength(), path + ".name", errors);
		String phone = (rawPhone == null) ? null : phone(field, rawPhone, path + ".phone", errors);
		if (name == null && phone == null) {
			return null;
		}
		return new GuardianDetails(name, phone);
	}

}
