package com.lms.exammanagement.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code ResultsPublishingService#publishResults} inside the
 * same transaction that sets {@code exam.results_published_at} (MVP-017 plan
 * §7/§9/§16), mirroring {@code CoursePriceChangedEvent}/{@code
 * PaymentConfirmedEvent}'s "ship now, no consumer needed yet" precedent. No
 * consumer exists yet - {@code notification-management} is the expected
 * eventual listener, and {@code audit-log-management} could additively
 * subscribe later with zero schema change if a future product decision
 * requires a mandatory audit-log entry for this action (plan §16 explicitly
 * defers that decision - this event exists so it can be honored either way
 * without a later breaking change).
 */
public record ExamResultPublishedEvent(UUID tenantId, UUID examId, UUID courseId, UUID publishedBy,
		Instant publishedAt) {

}
