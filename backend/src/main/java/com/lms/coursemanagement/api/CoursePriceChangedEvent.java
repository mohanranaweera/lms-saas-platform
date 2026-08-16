package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code CourseService#changePrice} inside the same
 * transaction that writes the {@code course_price_history} row (plan §16).
 * No consumer exists yet - {@code audit-log-management} is expected to be
 * the eventual listener that persists this into the platform's canonical
 * audit log, per {@code .claude/rules/architecture.md}'s domain-event
 * pattern for fan-out to that domain. Placed in {@code api} (not {@code
 * course.domain}) since it is this module's public contract for other
 * domains to depend on, not an internal implementation detail.
 */
public record CoursePriceChangedEvent(UUID tenantId, UUID courseId, UUID changedBy, BigDecimal previousPrice,
		BigDecimal newPrice, Instant changedAt) {

}
