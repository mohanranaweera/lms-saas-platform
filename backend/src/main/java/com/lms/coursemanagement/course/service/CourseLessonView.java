package com.lms.coursemanagement.course.service;

import java.time.Instant;
import java.util.UUID;

public record CourseLessonView(UUID id, UUID moduleId, String title, Integer sequence, Instant createdAt,
		Instant updatedAt) {

}
