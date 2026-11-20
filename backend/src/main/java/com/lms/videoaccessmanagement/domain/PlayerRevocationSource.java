package com.lms.videoaccessmanagement.domain;

/**
 * {@code player_installation.revocation_source} (V77, VIDEO Phase 8): who or
 * what revoked an installation. {@link #REPAIRED} = a new pairing on the same
 * device replaced it; {@link #DEVICE_REVOKED} = its ADR-022 device was revoked.
 */
public enum PlayerRevocationSource {
	STUDENT, ADMIN, DEVICE_REVOKED, REPAIRED
}
