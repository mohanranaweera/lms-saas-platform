package com.lms.videoaccessmanagement.config;

import java.net.URI;
import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.player.*} (VIDEO Phase 8, ADR-025). {@code launchCodePepper}
 * ({@code PLAYER_LAUNCH_CODE_PEPPER}, ADR-019) keys the HMAC under which
 * pairing and launch codes are stored; blank fails closed. {@code minVersion}
 * is the kill switch for old players.
 */
@Component
@ConfigurationProperties(prefix = "app.player")
public class PlayerProperties {

	private String launchCodePepper = "";

	private String minVersion = "0.1.0";

	private int pairingCodeTtlSeconds = 90;

	/** VIDEO Phase 13c: the DynTube bridge page URL (ADR-026 Amendment 1); blank fails closed. */
	private String dyntubeBridgeUrl = "";

	private boolean allowInsecureBridge;

	public String getDyntubeBridgeUrl() {
		return dyntubeBridgeUrl;
	}

	/**
	 * Returns the configured bridge only when it is a clean HTTPS page (or a
	 * loopback HTTP page in local/test configuration). The bridge is a fixed
	 * application asset, not a caller-selected navigation target.
	 */
	public boolean hasSafeDyntubeBridgeUrl(boolean allowLoopbackHttp) {
		return isSafeDyntubeBridgeUrl(dyntubeBridgeUrl, allowLoopbackHttp);
	}

	public static boolean isSafeDyntubeBridgeUrl(String raw, boolean allowLoopbackHttp) {
		if (raw == null || raw.length() > 512 || raw.isBlank()) {
			return false;
		}
		try {
			URI uri = URI.create(raw.trim());
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			boolean localHttpHost = "localhost".equalsIgnoreCase(uri.getHost())
					|| "127.0.0.1".equals(uri.getHost())
					|| "embed.lms.test".equalsIgnoreCase(uri.getHost());
			boolean loopbackHttp = allowLoopbackHttp && "http".equals(scheme) && localHttpHost;
			if (!("https".equals(scheme) || loopbackHttp)
					|| uri.getHost() == null
					|| uri.getUserInfo() != null
					|| uri.getQuery() != null
					|| uri.getFragment() != null
					|| !"/embed/dyntube-bridge.html".equals(uri.getPath())) {
				return false;
			}
			return uri.getPort() >= -1;
		}
		catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	public void setDyntubeBridgeUrl(String dyntubeBridgeUrl) {
		this.dyntubeBridgeUrl = dyntubeBridgeUrl;
	}

	public boolean isAllowInsecureBridge() {
		return allowInsecureBridge;
	}

	public void setAllowInsecureBridge(boolean allowInsecureBridge) {
		this.allowInsecureBridge = allowInsecureBridge;
	}

	public String getLaunchCodePepper() {
		return launchCodePepper;
	}

	public void setLaunchCodePepper(String launchCodePepper) {
		this.launchCodePepper = launchCodePepper;
	}

	public String getMinVersion() {
		return minVersion;
	}

	public void setMinVersion(String minVersion) {
		this.minVersion = minVersion;
	}

	public int getPairingCodeTtlSeconds() {
		return pairingCodeTtlSeconds;
	}

	public void setPairingCodeTtlSeconds(int pairingCodeTtlSeconds) {
		this.pairingCodeTtlSeconds = pairingCodeTtlSeconds;
	}

}
