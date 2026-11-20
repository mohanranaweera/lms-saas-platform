package com.lms.videoaccessmanagement.service;

/**
 * VIDEO Phase 13c (ADR-026 Amendment 1): a video that plays in its host's own
 * player, framed through our bridge page. {@code key} is the host's embed key
 * (DynTube {@code channelKey}) - not a secret, but handed out only inside an
 * authorized playback session. {@code bridgeUrl} is the configured bridge page
 * on the dedicated embed origin; the AOC Player ignores it and frames only its
 * compiled-in bridge.
 */
public record PlaybackEmbed(String provider, String key, String bridgeUrl) {

	@Override
	public String toString() {
		return "PlaybackEmbed[provider=" + provider + "]";
	}

}
