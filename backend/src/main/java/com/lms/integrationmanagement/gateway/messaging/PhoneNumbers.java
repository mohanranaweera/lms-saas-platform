package com.lms.integrationmanagement.gateway.messaging;

import java.util.Optional;

/**
 * Turns a stored phone number into international digits without {@code +}
 * (ADR-024 V5) - the form both Text.lk and Meta expect. Student mobiles are
 * free text, so separators are dropped; {@code 00} is an international prefix;
 * a single leading {@code 0} is a national number and gets the default
 * country code. Anything outside 8-15 digits is unusable.
 */
public final class PhoneNumbers {

	private PhoneNumbers() {
	}

	public static Optional<String> toInternationalDigits(String raw, String defaultCountryCode) {
		if (raw == null) {
			return Optional.empty();
		}
		String trimmed = raw.trim();
		boolean plus = trimmed.startsWith("+");
		String digits = trimmed.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) {
			return Optional.empty();
		}
		if (!plus) {
			if (digits.startsWith("00")) {
				digits = digits.substring(2);
			}
			else if (digits.startsWith("0")) {
				String country = (defaultCountryCode == null) ? "" : defaultCountryCode.replaceAll("[^0-9]", "");
				if (country.isEmpty()) {
					return Optional.empty();
				}
				digits = country + digits.substring(1);
			}
		}
		if (digits.length() < 8 || digits.length() > 15 || digits.startsWith("0")) {
			return Optional.empty();
		}
		return Optional.of(digits);
	}

}
