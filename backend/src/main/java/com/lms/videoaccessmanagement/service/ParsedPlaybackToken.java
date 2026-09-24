package com.lms.videoaccessmanagement.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Validated claims extracted from a playback JWT by {@link
 * PlaybackTokenService#parseAndValidate} - never a raw {@code
 * io.jsonwebtoken.Claims} exposed past this service, mirroring {@code
 * identityaccessservice.service.ParsedToken}'s shape/purpose exactly for a
 * structurally distinct token type.
 */
public record ParsedPlaybackToken(UUID studentId, UUID tenantId, UUID videoAssetId, UUID watchSessionId, UUID jti,
		Instant expiresAt) {

}
