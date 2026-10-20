package com.lms.coursemanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One lesson's access/payment-relevant metadata, returned in batch by {@link
 * CourseLookupApi#findLessonPolicies(UUID)} for the lesson access evaluator
 * and the payment composer (KLASS-T4b/T4c). Safe metadata only - never a
 * resource URL.
 *
 * @param price {@code null} unless {@code paymentType} is {@link
 * LessonPaymentType#PAID}.
 * @param billingYear server-derived billing month for Monthly covered sessions
 * (both-or-neither with {@code billingMonth}), from the session start in the
 * tenant timezone; never parsed from titles.
 */
public record LessonPolicy(UUID lessonId, UUID moduleId, String moduleTitle, int moduleSequence, String title,
		String lessonLabel, int sequence, LessonResourceType resourceType, LessonPaymentType paymentType,
		BigDecimal price, Instant startsAt, Instant endsAt, Integer billingYear, Integer billingMonth) {

}
