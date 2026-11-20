package com.lms.videoaccessmanagement.domain;

/**
 * Status of a one-time player code (V77, VIDEO Phase 8): {@code
 * player_pairing_code.status} uses ISSUED/CONSUMED/EXPIRED; {@code
 * playback_launch_code.status} also uses REVOKED (its session was revoked).
 */
public enum OneTimeCodeStatus {
	ISSUED, CONSUMED, EXPIRED, REVOKED
}
