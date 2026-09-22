package com.lms.coursemanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code CourseService#cloneCourse} inside the same transaction
 * as the clone's {@code course} row (and its copied {@code course_module}/
 * {@code course_lesson} rows) insert - mirrors {@link
 * CourseArchiveStateChangedEvent}'s exact shape (source of truth: the
 * archive/unarchive/pricing-model-changed events already audited by {@code
 * AuditLogEventListener}). Added per ADR-015's Fix 3 (Phase E architecture
 * review): cloning previously published no domain event at all, so it was
 * silently invisible to {@code audit-log-management}, inconsistent with
 * every other course mutation ({@code course.archived}, {@code
 * course.pricing_model_changed}, {@code course.price_changed}, etc.).
 *
 * @param sourceCourseId the course that was cloned from.
 * @param newCourseId the newly-created clone's id.
 * @param actorId the caller who performed the clone.
 */
public record CourseClonedEvent(UUID tenantId, UUID sourceCourseId, UUID newCourseId, UUID actorId,
		Instant clonedAt) {

}
