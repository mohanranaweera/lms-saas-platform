package com.lms.identityaccessservice.service;

/**
 * Builds the human-readable {@code device.label} ("Chrome on Windows") from
 * the User-Agent. Display only - the label is never used to identify or match
 * a device (Wave 10 brief: no User-Agent-based identity).
 */
public final class DeviceLabels {

	private static final int MAX_LABEL_LENGTH = 120;

	private DeviceLabels() {
	}

	public static String fromUserAgent(String userAgent) {
		if (userAgent == null || userAgent.isBlank()) {
			return "Unknown device";
		}
		String label = browser(userAgent) + " on " + operatingSystem(userAgent);
		return label.length() > MAX_LABEL_LENGTH ? label.substring(0, MAX_LABEL_LENGTH) : label;
	}

	private static String browser(String ua) {
		if (ua.contains("Edg/")) {
			return "Edge";
		}
		if (ua.contains("OPR/") || ua.contains("Opera")) {
			return "Opera";
		}
		if (ua.contains("Firefox/")) {
			return "Firefox";
		}
		if (ua.contains("Chrome/") || ua.contains("CriOS/")) {
			return "Chrome";
		}
		if (ua.contains("Safari/")) {
			return "Safari";
		}
		return "Browser";
	}

	private static String operatingSystem(String ua) {
		if (ua.contains("Android")) {
			return "Android";
		}
		if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) {
			return "iOS";
		}
		if (ua.contains("Windows")) {
			return "Windows";
		}
		if (ua.contains("CrOS")) {
			return "ChromeOS";
		}
		if (ua.contains("Mac OS X") || ua.contains("Macintosh")) {
			return "macOS";
		}
		if (ua.contains("Linux")) {
			return "Linux";
		}
		return "unknown OS";
	}

}
