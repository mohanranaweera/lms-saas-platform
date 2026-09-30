package com.lms.tenantmanagement.service;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * TEN-DOM-01 / ADR-021: hostname parsing and validation, shared by tenant
 * resolution and the custom-domain service.
 */
final class Hostnames {

	private static final Pattern LABEL = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$");

	/** The last label must start with a letter: no bare IP addresses. */
	private static final Pattern TOP_LABEL = Pattern.compile("^[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$");

	private static final int MAX_LENGTH = 253;

	private Hostnames() {
	}

	/**
	 * The host of a request's {@code Host} header: lower case, without port or
	 * trailing dot. {@code null} for a blank header or an IPv6 literal (never a
	 * tenant).
	 */
	static String requestHost(String hostHeader) {
		if (hostHeader == null || hostHeader.isBlank()) {
			return null;
		}
		String host = hostHeader.trim().toLowerCase(Locale.ROOT);
		if (host.startsWith("[")) {
			return null;
		}
		int colon = host.lastIndexOf(':');
		if (colon >= 0) {
			host = host.substring(0, colon);
		}
		host = stripTrailingDots(host);
		return host.isEmpty() ? null : host;
	}

	/** A tenant-entered hostname in stored form: trimmed, lower case, no trailing dot. */
	static String normalise(String raw) {
		return (raw == null) ? "" : stripTrailingDots(raw.trim().toLowerCase(Locale.ROOT));
	}

	/**
	 * Why {@code host} (already {@link #normalise normalised}) can't be a custom
	 * domain, or empty when it can.
	 */
	static Optional<String> problem(String host, String platformDomain) {
		if (host.isEmpty()) {
			return Optional.of("is required");
		}
		if (host.length() > MAX_LENGTH) {
			return Optional.of("must be at most " + MAX_LENGTH + " characters");
		}
		String[] labels = host.split("\\.", -1);
		if (labels.length < 2) {
			return Optional.of("must be a full domain name, such as learn.example.com");
		}
		for (String label : labels) {
			if (!LABEL.matcher(label).matches()) {
				return Optional.of("is not a valid domain name (letters, digits and hyphens only, no scheme or path)");
			}
		}
		if (!TOP_LABEL.matcher(labels[labels.length - 1]).matches()) {
			return Optional.of("is not a valid domain name");
		}
		if (host.equals("localhost") || host.endsWith(".localhost")) {
			return Optional.of("is not allowed");
		}
		if (!platformDomain.isEmpty() && (host.equals(platformDomain) || host.endsWith("." + platformDomain))) {
			return Optional.of("must be your own domain, not an address on this platform");
		}
		return Optional.empty();
	}

	/**
	 * {@code template}'s scheme, port and path around a different host, e.g.
	 * {@code http://{subdomain}.lms.test:3000} + {@code learn.abc.test} =
	 * {@code http://learn.abc.test:3000}.
	 */
	static String withHost(String template, String host) {
		int schemeEnd = template.indexOf("://");
		if (schemeEnd < 0) {
			return "https://" + host;
		}
		String scheme = template.substring(0, schemeEnd);
		String rest = template.substring(schemeEnd + 3);
		int slash = rest.indexOf('/');
		String authority = (slash < 0) ? rest : rest.substring(0, slash);
		String path = (slash < 0) ? "" : rest.substring(slash);
		int colon = authority.lastIndexOf(':');
		String port = (colon < 0) ? "" : authority.substring(colon);
		return scheme + "://" + host + port + path;
	}

	private static String stripTrailingDots(String value) {
		String result = value;
		while (result.endsWith(".")) {
			result = result.substring(0, result.length() - 1);
		}
		return result;
	}

}
