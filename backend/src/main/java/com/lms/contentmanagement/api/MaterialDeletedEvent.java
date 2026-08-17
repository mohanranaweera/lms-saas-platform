package com.lms.contentmanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published in the same {@code @Transactional} boundary as {@code
 * MaterialService#deleteMaterial}, per {@code .claude/rules/security.md}'s
 * audit-logging requirement ("material... content deletions" is on the
 * mandatory audit list; the write must happen server-side inside the same
 * transaction as the privileged action). No {@code audit-log-management}
 * consumer exists yet - this event exists so one can be added later without
 * touching this module, mirroring {@code
 * coursemanagement.api.CoursePriceChangedEvent}'s precedent exactly. Carries
 * a full before-state snapshot since a delete has no "after" state.
 */
public record MaterialDeletedEvent(UUID tenantId, UUID materialId, UUID lessonId, UUID moduleId, UUID courseId,
		String title, String mimeType, String storageObjectKey, UUID uploadedBy, UUID deletedBy, Instant deletedAt) {

}
