package com.lms.common.logging;

/**
 * Display-safe forms of a recipient's contact details (Wave 11): used for
 * delivery logs, structured log lines and API responses, so a full phone
 * number or email address is never written anywhere but the outbound message
 * itself.
 */
public final class ContactMasking {

	private static final String DOT = "•••";

	private ContactMasking() {
	}

	/** {@code +94771234567} -> {@code +9477•••567}. Short or empty input -> {@code •••}. */
	public static String maskPhone(String phone) {
		if (phone == null) {
			return null;
		}
		String trimmed = phone.replaceAll("\\s", "");
		if (trimmed.length() < 7) {
			return DOT;
		}
		return trimmed.substring(0, 4) + DOT + trimmed.substring(trimmed.length() - 3);
	}

	/** {@code jane.doe@example.com} -> {@code j•••@example.com}. */
	public static String maskEmail(String email) {
		if (email == null) {
			return null;
		}
		int at = email.indexOf('@');
		if (at <= 0) {
			return DOT;
		}
		return email.charAt(0) + DOT + email.substring(at);
	}

}
