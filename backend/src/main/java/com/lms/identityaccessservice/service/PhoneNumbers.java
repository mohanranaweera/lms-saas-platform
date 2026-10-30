package com.lms.identityaccessservice.service;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import java.util.Locale;
import java.util.Optional;

/**
 * ADR-031: one normalisation for sign-in mobiles, used both when a number is
 * stored and when someone signs in with it, so {@code 0771234567},
 * {@code 771234567} and {@code +94 77 123 4567} all match the same account.
 * A number without a country code is read with the tenant's default region;
 * {@code +…} keeps its own country. Only valid mobile / fixed-line-or-mobile
 * numbers are accepted. The V108 backfill applies the same rules.
 */
public final class PhoneNumbers {

	public static final String DEFAULT_REGION = "LK";

	private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();

	private PhoneNumbers() {
	}

	/** E.164 form of {@code raw}, or empty when it isn't a valid mobile number. */
	public static Optional<String> toE164(String raw, String region) {
		if (raw == null || raw.isBlank() || raw.length() > 40) {
			return Optional.empty();
		}
		try {
			PhoneNumber number = UTIL.parse(raw.strip(), effectiveRegion(region));
			if (!UTIL.isValidNumber(number)) {
				return Optional.empty();
			}
			PhoneNumberType type = UTIL.getNumberType(number);
			if (type != PhoneNumberType.MOBILE && type != PhoneNumberType.FIXED_LINE_OR_MOBILE) {
				return Optional.empty();
			}
			return Optional.of(UTIL.format(number, PhoneNumberFormat.E164));
		}
		catch (NumberParseException ex) {
			return Optional.empty();
		}
	}

	/** Whether {@code region} is an ISO 3166-1 alpha-2 region libphonenumber knows. */
	public static boolean isSupportedRegion(String region) {
		return region != null && UTIL.getSupportedRegions().contains(region);
	}

	/** Last three digits only, for audit entries and logs ({@code ***567}). */
	public static String masked(String e164) {
		return e164 == null ? null : "***" + e164.substring(Math.max(0, e164.length() - 3));
	}

	private static String effectiveRegion(String region) {
		String upper = region == null ? null : region.toUpperCase(Locale.ROOT);
		return isSupportedRegion(upper) ? upper : DEFAULT_REGION;
	}

}
