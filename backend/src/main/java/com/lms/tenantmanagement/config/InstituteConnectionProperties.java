package com.lms.tenantmanagement.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * INST-QR-01 settings (all non-secret, ADR-019 Decision 3):
 * <ul>
 * <li>{@code base-url} ({@code INSTITUTE_CONNECTION_BASE_URL}): the platform-owned
 * origin that serves {@code /connect/v1/{code}} - scheme and host (and optional port)
 * only. Empty = connection links are unavailable.</li>
 * <li>{@code require-https}: true everywhere except the local profile.</li>
 * <li>{@code android-app-url} / {@code ios-app-url}: store listings of OUR student
 * app. Empty until a release is published. https only.</li>
 * </ul>
 * A set but malformed value fails startup instead of producing broken QR codes.
 */
@Component
@ConfigurationProperties(prefix = "app.institute-connection")
public class InstituteConnectionProperties {

	private String baseUrl = "";

	private boolean requireHttps = true;

	private String androidAppUrl = "";

	private String iosAppUrl = "";

	@PostConstruct
	void validate() {
		if (StringUtils.hasText(baseUrl)) {
			URI uri = parse("app.institute-connection.base-url", baseUrl);
			String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
			boolean schemeOk = "https".equals(scheme) || (!requireHttps && "http".equals(scheme));
			boolean originOnly = (uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
					&& uri.getRawQuery() == null && uri.getRawFragment() == null && uri.getRawUserInfo() == null;
			if (!schemeOk || !originOnly) {
				throw new IllegalStateException("app.institute-connection.base-url must be an "
						+ (requireHttps ? "https" : "http(s)") + " origin with no path, query or credentials");
			}
		}
		requireHttpsOrEmpty("app.institute-connection.android-app-url", androidAppUrl);
		requireHttpsOrEmpty("app.institute-connection.ios-app-url", iosAppUrl);
	}

	/** The configured origin without a trailing slash, or empty. */
	public String getBaseUrl() {
		String trimmed = baseUrl == null ? "" : baseUrl.trim();
		while (trimmed.endsWith("/")) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public boolean isRequireHttps() {
		return requireHttps;
	}

	public void setRequireHttps(boolean requireHttps) {
		this.requireHttps = requireHttps;
	}

	public String getAndroidAppUrl() {
		return blankToNull(androidAppUrl);
	}

	public void setAndroidAppUrl(String androidAppUrl) {
		this.androidAppUrl = androidAppUrl;
	}

	public String getIosAppUrl() {
		return blankToNull(iosAppUrl);
	}

	public void setIosAppUrl(String iosAppUrl) {
		this.iosAppUrl = iosAppUrl;
	}

	public boolean isConfigured() {
		return !getBaseUrl().isEmpty();
	}

	private static void requireHttpsOrEmpty(String name, String value) {
		if (StringUtils.hasText(value) && !"https".equalsIgnoreCase(parse(name, value).getScheme())) {
			throw new IllegalStateException(name + " must be an https URL");
		}
	}

	private static URI parse(String name, String value) {
		try {
			URI uri = new URI(value.trim());
			if (uri.getScheme() == null || uri.getHost() == null) {
				throw new IllegalStateException(name + " must be an absolute URL");
			}
			return uri;
		}
		catch (URISyntaxException ex) {
			throw new IllegalStateException(name + " is not a valid URL", ex);
		}
	}

	private static String blankToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
