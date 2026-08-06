package com.lms.identityaccessservice.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Computes {@code device_session.device_identifier_hash} entirely
 * server-side from request signals already available to the server
 * (User-Agent + remote address) - never from a client-supplied device id
 * (per .claude/rules/security.md's device-authentication rule). No
 * device-limit/registration enforcement reads this value yet (Phase 2,
 * explicitly out of scope for this module) - it is populated now purely so
 * the column is meaningful once that enforcement lands.
 */
final class DeviceFingerprint {

	private DeviceFingerprint() {
	}

	static String hash(HttpServletRequest request) {
		String userAgent = request.getHeader("User-Agent");
		String signal = (userAgent == null ? "unknown-agent" : userAgent) + "|" + request.getRemoteAddr();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(signal.getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

}
