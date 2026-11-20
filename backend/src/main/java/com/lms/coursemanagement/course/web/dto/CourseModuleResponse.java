package com.lms.coursemanagement.course.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CourseModuleResponse(UUID id, UUID courseId, String title, Integer sequence, BigDecimal price,
		Instant createdAt, Instant updatedAt) {

}
