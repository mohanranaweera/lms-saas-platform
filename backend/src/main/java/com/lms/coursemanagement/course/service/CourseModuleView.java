package com.lms.coursemanagement.course.service;

import java.time.Instant;
import java.util.UUID;

public record CourseModuleView(UUID id, UUID courseId, String title, Integer sequence, Instant createdAt,
		Instant updatedAt) {

}
