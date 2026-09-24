package com.lms.liveclassmanagement.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Service-layer command for scheduling a new {@code class_session}. {@code
 * teacherId} is deliberately ABSENT - it is always derived server-side from
 * {@code CourseLookupApi#getTeacherId(courseId)}, never client-supplied (see
 * {@code ClassSessionSchedulingService#scheduleSession}).
 */
public record NewSessionCommand(UUID courseId, UUID lessonId, String title, String description,
		Instant scheduledStart, Instant scheduledEnd) {

}
