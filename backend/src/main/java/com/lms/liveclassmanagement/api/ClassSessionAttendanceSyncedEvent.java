package com.lms.liveclassmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when the meeting provider reports that automatic attendance data
 * is available for a {@code class_session} ({@code "session.attendance_synced"}
 * webhook event). Publish-only this wave (Wave 4 plan §11) - no listener
 * exists yet in {@code attendance-management}; full consumption (writing
 * {@code attendance_record} rows from provider-synced data) is deferred to
 * Wave 8, matching {@code implementation-roadmap.md}'s "Wave 4/8" split for
 * PAR-10-03. Carries only opaque ids, never provider-specific attendance
 * payload shape, so a future consumer is not coupled to this wave's webhook
 * parsing.
 */
public record ClassSessionAttendanceSyncedEvent(UUID tenantId, UUID sessionId, UUID courseId, Instant syncedAt) {

}
