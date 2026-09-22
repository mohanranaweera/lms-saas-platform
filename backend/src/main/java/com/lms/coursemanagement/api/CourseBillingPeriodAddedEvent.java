package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code BillingConfigurationService#addBillingPeriod} inside
 * the same transaction that closes the prior open period (if any) and inserts
 * the new one, mirroring {@link CoursePriceChangedEvent}'s exact role. {@code
 * previousAmount} is {@code null} when this is the first period ever added
 * for the billing configuration (nothing to close).
 */
public record CourseBillingPeriodAddedEvent(UUID tenantId, UUID courseId, UUID billingConfigurationId,
		UUID newPeriodId, UUID addedBy, BigDecimal previousAmount, BigDecimal newAmount, String currency,
		Instant effectiveFrom, Instant changedAt) {

}
