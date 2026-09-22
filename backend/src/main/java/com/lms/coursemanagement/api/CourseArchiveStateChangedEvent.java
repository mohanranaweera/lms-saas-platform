package com.lms.coursemanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code CourseService#archiveCourse}/{@code #unarchiveCourse}
 * inside the same transaction as the {@code course.archived_at} write.
 * {@code archived} is {@code true} for an archive transition, {@code false}
 * for an unarchive transition - a single event type covers both, mirroring
 * how {@code audit-log-management}'s {@code onTenantStatusChanged} listener
 * already branches one event into two distinct {@code action} strings.
 */
public record CourseArchiveStateChangedEvent(UUID tenantId, UUID courseId, UUID actorId, boolean archived,
		Instant changedAt) {

}
