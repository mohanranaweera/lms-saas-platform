package com.lms.coursemanagement.course.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** {@code price} (KLASS-T4a): the MODULE-offer price, {@code null} when not sold separately. */
public record CourseModuleView(UUID id, UUID courseId, String title, Integer sequence, BigDecimal price,
		Instant createdAt, Instant updatedAt) {

}
