package com.lms.paymentmanagement.api;

import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * PAY-HUB-01: parses a staff-typed order number ({@code ORD-000123},
 * {@code 000123} or {@code 123}, case-insensitive, surrounding blanks
 * ignored) into the tenant-local {@code student_order.order_number}. The
 * display form itself is produced by {@code StudentOrder#getDisplayOrderNumber}.
 */
public final class OrderNumberFormat {

	private static final Pattern ORDER_NUMBER = Pattern.compile("(?:ORD-)?(\\d{1,12})");

	private OrderNumberFormat() {
	}

	/** Empty for anything that is not an order number (including blank input). */
	public static OptionalLong parse(String value) {
		if (value == null) {
			return OptionalLong.empty();
		}
		var matcher = ORDER_NUMBER.matcher(value.strip().toUpperCase(Locale.ROOT));
		if (!matcher.matches()) {
			return OptionalLong.empty();
		}
		long number = Long.parseLong(matcher.group(1));
		return number > 0 ? OptionalLong.of(number) : OptionalLong.empty();
	}

}
