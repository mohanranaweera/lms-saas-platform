package com.lms.videoaccessmanagement.support;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Computes {@code video_watch_session.device_fingerprint_hash} entirely
 * server-side from request signals already available to the server
 * (User-Agent + remote address) - never from a client-supplied device id,
 * per {@code .claude/rules/security.md}'s device-authentication rule.
 *
 * <p><b>Deliberate duplication, not reuse - documented design choice:</b>
 * {@code identityaccessservice.web.DeviceFingerprint} already implements
 * this exact SHA-256-of-(User-Agent + remote address) technique, and the
 * task brief for this module asked to "reuse" it. It cannot actually be
 * reused: that class is package-private ({@code final class DeviceFingerprint},
 * no {@code public} modifier) inside {@code identityaccessservice.web} - not
 * even visible outside its own package, let alone importable across the
 * {@code video-access-management}/{@code identity-access-service} module
 * boundary. Making it {@code public} and importing it from here would also
 * violate {@code .claude/rules/architecture.md}'s "a module may depend only
 * on another module's {@code api} package" rule (that class lives in {@code
 * web}, not {@code api}), and {@code identity-access-service} is documented
 * as a foundational module other domains may depend on - but only through
 * its {@code api} package, which this hashing utility is not part of and is
 * not worth promoting into for a five-line, side-effect-free algorithm.
 * Duplicating this small, self-contained allow-list-style utility inside
 * {@code video-access-management} itself keeps the module boundary clean,
 * mirroring the same choice already made for video-MIME sniffing (see
 * {@code VideoContentSniffer}'s javadoc for the identical rationale applied
 * to a different shared utility).
 */
public final class RequestDeviceFingerprint {

	private RequestDeviceFingerprint() {
	}

	public static String hash(HttpServletRequest request) {
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
