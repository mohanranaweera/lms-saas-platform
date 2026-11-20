package com.lms.identityaccessservice.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Computes {@code platform_admin_session.device_identifier_hash} from request
 * signals (User-Agent + remote address). Since Wave 10 this is used ONLY for
 * Platform Admin sessions, which are not device-limited. Tenant users are
 * identified by a server-issued device token instead (ADR-007 section 2,
 * {@code DeviceRegistrationService}) - this hash is never device identity.
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
