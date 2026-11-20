package com.lms.identityaccessservice.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (synchronously, inside the revoke transaction) when an ADR-022
 * device is revoked - by an admin reset or the user's self-service removal.
 * VIDEO Phase 8: video-access-management revokes the AOC Player installations
 * paired on that device and their live playback sessions. Identity only
 * publishes; it never depends back on the listening business modules.
 */
public record DeviceRevokedEvent(UUID tenantId, UUID userId, UUID deviceId, UUID actorId, Instant revokedAt) {

}
