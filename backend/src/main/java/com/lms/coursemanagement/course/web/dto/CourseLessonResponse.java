package com.lms.coursemanagement.course.web.dto;

import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonResourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** KLASS-T4a adds the session fields and {@code expired} (endsAt before now). */
public record CourseLessonResponse(UUID id, UUID moduleId, String title, Integer sequence, Instant startsAt,
		Instant endsAt, LessonResourceType resourceType, LessonPaymentType paymentType, BigDecimal price,
		String description, String lessonLabel, Integer billingYear, Integer billingMonth, boolean expired,
		Instant createdAt, Instant updatedAt) {

}
