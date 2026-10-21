package com.lms.integrationmanagement.gateway.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;

/**
 * DynTube token authentication (ADR-026, verified live 2026-10-10):
 * {@code token = base64url(SHA-256(securityKey + expires + countries + ips + videoKey))}
 * without padding, {@code expires} in Unix <b>milliseconds</b>. No country,
 * IP or view restriction is used (ADR-026 consequence 3).
 */
public final class DynTubeTokenSigner {

	private DynTubeTokenSigner() {
	}

	/** The tokenized master-playlist URL; the caller has validated {@code videoKey}. */
	public static String hlsUrl(String apiBaseUrl, String securityKey, String videoKey, Instant expiresAt) {
		long expires = expiresAt.toEpochMilli();
		return apiBaseUrl + "/live/videos/tokens/" + videoKey + ".m3u8?token=" + token(securityKey, videoKey, expires)
				+ "&expires=" + expires;
	}

	static String token(String securityKey, String videoKey, long expiresMillis) {
		// countries and ips are empty strings in the input (no restriction).
		String input = securityKey + expiresMillis + videoKey;
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
