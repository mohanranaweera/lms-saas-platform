package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonResourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** KLASS-T4a adds the session fields and {@code expired} ({@code endsAt} before now). */
public record CourseLessonView(UUID id, UUID moduleId, String title, Integer sequence, Instant startsAt,
		Instant endsAt, LessonResourceType resourceType, LessonPaymentType paymentType, BigDecimal price,
		String description, String lessonLabel, Integer billingYear, Integer billingMonth, boolean expired,
		Instant createdAt, Instant updatedAt) {

}
