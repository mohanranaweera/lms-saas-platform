package com.lms.integrationmanagement.gateway;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Zoom webhook authenticity (MEDIA-INT-01), per Zoom's documented scheme:
 * {@code x-zm-signature = "v0=" + hex(HMAC-SHA256(secretToken,
 * "v0:" + x-zm-request-timestamp + ":" + rawBody))}. Fails closed: an empty
 * secret, a missing header, or a timestamp more than 5 minutes from now is
 * rejected. Comparison is constant-time.
 */
public final class ZoomWebhookSignatures {

	private static final Duration MAX_SKEW = Duration.ofMinutes(5);

	private ZoomWebhookSignatures() {
	}

	public static boolean verify(String secretToken, String timestampHeader, String rawBody, String signatureHeader,
			Instant now) {
		if (secretToken == null || secretToken.isBlank() || timestampHeader == null || signatureHeader == null
				|| rawBody == null) {
			return false;
		}
		long timestamp;
		try {
			timestamp = Long.parseLong(timestampHeader.trim());
		}
		catch (NumberFormatException ex) {
			return false;
		}
		// Zoom sends seconds; tolerate milliseconds too.
		Instant sentAt = (timestamp > 100_000_000_000L) ? Instant.ofEpochMilli(timestamp)
				: Instant.ofEpochSecond(timestamp);
		if (Duration.between(sentAt, now).abs().compareTo(MAX_SKEW) > 0) {
			return false;
		}
		String expected = "v0=" + hmacHex(secretToken, "v0:" + timestampHeader.trim() + ":" + rawBody);
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
				signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
	}

	/** The {@code endpoint.url_validation} response token: hex(HMAC-SHA256(secretToken, plainToken)). */
	public static String encryptPlainToken(String secretToken, String plainToken) {
		return hmacHex(secretToken, plainToken);
	}

	private static String hmacHex(String secret, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
		}
	}

}
