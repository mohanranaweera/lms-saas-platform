package com.lms.usermanagement.student.domain;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * STU-MGMT-02 (spec section 7): the opaque student QR token - 24 random bytes (192
 * bits) from {@link SecureRandom}, lowercase hex (48 chars). The QR payload carries
 * only {@code LMS-STU:1:<token>}; no name, index, contact or credential data.
 */
public final class StudentQrTokens {

	public static final String PAYLOAD_PREFIX = "LMS-STU:1:";

	private static final SecureRandom RANDOM = new SecureRandom();

	private StudentQrTokens() {
	}

	public static String newToken() {
		byte[] bytes = new byte[24];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
	}

	public static String payload(String token) {
		return PAYLOAD_PREFIX + token;
	}

	/** The token of a well-formed payload, or {@code null}. */
	public static String tokenOf(String payload) {
		if (payload == null) {
			return null;
		}
		String trimmed = payload.strip();
		if (!trimmed.startsWith(PAYLOAD_PREFIX)) {
			return null;
		}
		String token = trimmed.substring(PAYLOAD_PREFIX.length());
		return token.matches("[0-9a-f]{48,64}") ? token : null;
	}

}
