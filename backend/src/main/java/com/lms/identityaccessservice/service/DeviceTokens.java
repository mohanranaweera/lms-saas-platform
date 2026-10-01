package com.lms.identityaccessservice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Server-issued device tokens (ADR-007 §2, Wave 10): 256 random bits,
 * URL-safe Base64 for the cookie, SHA-256 hex at rest. Never derived from
 * User-Agent, IP or any other client signal.
 */
public final class DeviceTokens {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final int MAX_TOKEN_LENGTH = 128;

	private DeviceTokens() {
	}

	public static String generate() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	public static String hash(String rawToken) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	/** A cookie value worth looking up at all (non-blank, bounded) - anything else is treated as "no device". */
	public static boolean isPlausible(String rawToken) {
		return rawToken != null && !rawToken.isBlank() && rawToken.length() <= MAX_TOKEN_LENGTH;
	}

}
