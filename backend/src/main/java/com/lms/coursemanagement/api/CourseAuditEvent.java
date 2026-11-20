package com.lms.coursemanagement.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Generic course-management audit event (KLASS-T4a): published synchronously
 * inside the same transaction as the audited write, and turned into exactly
 * one {@code audit_log} row by {@code AuditLogEventListener} (same contract
 * as {@link CoursePriceChangedEvent}: a failed audit write rolls the action
 * back). {@code tenantId} is informational only - the listener resolves the
 * tenant from {@code TenantContext}.
 *
 * @param action e.g. {@code course.created}, {@code course.registration_changed},
 * {@code course.commission_rate_changed}, {@code course_lesson.price_changed}.
 * @param targetEntity {@code course}, {@code course_module} or {@code course_lesson}.
 * @param metadata before/after detail; may be {@code null}. Null values are
 * dropped by the listener.
 */
public record CourseAuditEvent(UUID tenantId, UUID actorId, String action, String targetEntity, UUID targetId,
		Map<String, Object> metadata, Instant occurredAt) {

}
