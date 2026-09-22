package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code BillingConfigurationService#createOrUpdateConfiguration}
 * inside the same transaction that writes/updates the {@code
 * course_billing_configuration} row, mirroring {@link CoursePriceChangedEvent}'s
 * exact role/shape for a different field set. Consumed by {@code
 * audit-log-management}'s {@code AuditLogEventListener}.
 */
public record CourseBillingConfigurationChangedEvent(UUID tenantId, UUID courseId, UUID changedBy,
		BigDecimal sessionRate, String currency, boolean requiresManualQuote, boolean created, Instant changedAt) {

}
