package com.lms.videoaccessmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published exactly once, synchronously, whenever {@code
 * VideoPlaybackSessionService#recordProgress} revokes an {@code ACTIVE}
 * {@code video_watch_session} due to a device-fingerprint mismatch or a
 * {@code max_watch_duration_seconds} breach (Wave 5, PAR-20-02, {@code
 * .claude/rules/security.md}'s "IP/device anomaly... must trigger session
 * revocation server-side and an audit/security log entry" requirement).
 * Mirrors {@code contentmanagement.api.MaterialDeletedEvent}'s exact
 * mechanism: consumed by {@code
 * auditlogmanagement.service.AuditLogEventListener} via a plain (non
 * -transactional) {@code @EventListener}, so the audit write happens
 * synchronously inside the same still-open transaction as the revoke - never
 * a best-effort side effect. {@code video-access-management} never calls
 * {@code audit-log-management}'s repository/service directly, per {@code
 * .claude/rules/architecture.md}.
 */
public record VideoPlaybackSessionRevokedEvent(UUID tenantId, UUID watchSessionId, UUID videoAssetId,
		UUID studentId, String reason, Instant occurredAt) {

}
