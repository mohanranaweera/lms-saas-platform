package com.lms.integrationmanagement.gateway.messaging;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Meta webhook authenticity (ADR-024 V8): {@code X-Hub-Signature-256 =
 * "sha256=" + hex(HMAC-SHA256(appSecret, rawBody))}, computed over the exact
 * bytes received. Fails closed on a missing secret, header or body; the
 * comparison is constant-time.
 */
public final class MetaWebhookSignatures {

	private static final String PREFIX = "sha256=";

	private MetaWebhookSignatures() {
	}

	public static boolean verify(String appSecret, byte[] rawBody, String signatureHeader) {
		if (appSecret == null || appSecret.isBlank() || rawBody == null || signatureHeader == null) {
			return false;
		}
		String given = signatureHeader.trim().toLowerCase(Locale.ROOT);
		if (!given.startsWith(PREFIX)) {
			return false;
		}
		String expected = PREFIX + sign(appSecret, rawBody);
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
	}

	/** Lower-case hex HMAC-SHA256 of {@code rawBody}; also used by tests to build signed callbacks. */
	public static String sign(String appSecret, byte[] rawBody) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(rawBody));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", ex);
		}
	}

}
