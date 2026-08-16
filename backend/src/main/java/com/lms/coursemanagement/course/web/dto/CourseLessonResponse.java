package com.lms.coursemanagement.course.web.dto;

import java.time.Instant;
import java.util.UUID;

public record CourseLessonResponse(UUID id, UUID moduleId, String title, Integer sequence, Instant createdAt,
		Instant updatedAt) {

}
