package com.lms.integrationmanagement.gateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * PayHere's request hash and notification checksum (PAY-GW-01 / ADR-017).
 * MD5 is PayHere's mandated scheme and is used here only as PayHere's message
 * authentication - never reused anywhere else.
 *
 * <pre>
 * hash   = UPPER(MD5(merchant_id + order_id + amount(2dp) + currency + UPPER(MD5(secret))))
 * md5sig = UPPER(MD5(merchant_id + order_id + payhere_amount + payhere_currency + status_code + UPPER(MD5(secret))))
 * </pre>
 */
public final class PayHereSignatures {

	private PayHereSignatures() {
	}

	/** Amount exactly as PayHere expects it in the request: 2 decimal places, no grouping. */
	public static String formatAmount(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
	}

	public static String checkoutHash(String merchantId, String orderId, BigDecimal amount, String currency,
			String merchantSecret) {
		return md5Upper(merchantId + orderId + formatAmount(amount) + currency + md5Upper(merchantSecret));
	}

	/** Computed over the notification's own raw {@code payhere_amount}/{@code payhere_currency} strings. */
	public static String notificationSignature(String merchantId, String orderId, String payhereAmount,
			String payhereCurrency, String statusCode, String merchantSecret) {
		return md5Upper(merchantId + orderId + payhereAmount + payhereCurrency + statusCode + md5Upper(merchantSecret));
	}

	/** Constant-time comparison of two signatures, case-insensitive (PayHere sends uppercase hex). */
	public static boolean matches(String expected, String received) {
		if (expected == null || received == null) {
			return false;
		}
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
				received.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
	}

	static String md5Upper(String input) {
		try {
			MessageDigest md5 = MessageDigest.getInstance("MD5");
			return HexFormat.of().withUpperCase().formatHex(md5.digest(input.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("MD5 not available", ex);
		}
	}

}
