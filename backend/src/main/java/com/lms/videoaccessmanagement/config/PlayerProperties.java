package com.lms.videoaccessmanagement.config;

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
