package com.lms.integrationmanagement.gateway;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

/**
 * Real HMAC-SHA256 webhook signature verification - even though {@link
 * com.lms.integrationmanagement.gateway.FakePaymentGatewayAdapter} is a
 * deterministic, in-process placeholder gateway with no real vendor SDK,
 * PAY-2's webhook-security requirement (per {@code .claude/rules/security.md})
 * is not something to fake. The signature is a lower-case hex-encoded digest
 * of {@code rawBody} keyed with the configured secret, compared in constant
 * time to defeat timing side channels.
 */
public final class WebhookSignatureVerifier {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private WebhookSignatureVerifier() {
	}

	/**
	 * @return {@code false} (fail closed) if {@code secret} is blank/unset,
	 * if either argument is blank, or if the computed digest does not match
	 * {@code signatureHeader} - never throws for a malformed/tampered/missing
	 * signature.
	 */
	public static boolean verify(String rawBody, String signatureHeader, String secret) {
		if (!StringUtils.hasText(secret) || !StringUtils.hasText(signatureHeader) || rawBody == null) {
			return false;
		}
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
			byte[] expected = hexToBytes(signatureHeader.trim());
			if (expected == null) {
				return false;
			}
			return MessageDigest.isEqual(computed, expected);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			return false;
		}
	}

	public static String sign(String rawBody, String secret) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			byte[] computed = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
			return bytesToHex(computed);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException ex) {
			throw new IllegalStateException("Unable to compute HMAC-SHA256 signature", ex);
		}
	}

	private static byte[] hexToBytes(String hex) {
		if (hex.length() % 2 != 0) {
			return null;
		}
		try {
			byte[] bytes = new byte[hex.length() / 2];
			for (int i = 0; i < bytes.length; i++) {
				int index = i * 2;
				bytes[i] = (byte) Integer.parseInt(hex.substring(index, index + 2), 16);
			}
			return bytes;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) {
			builder.append(String.format("%02x", b));
		}
		return builder.toString();
	}

}
