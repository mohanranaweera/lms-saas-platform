package com.lms.coursemanagement.course.web.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * Namespace holder for the small KLASS-T4a request/response bodies (kept in
 * one file to avoid a dozen two-line files). Each nested record is a plain
 * public DTO.
 */
public final class CourseKlassRequests {

	private CourseKlassRequests() {
	}

	/** {@code PATCH /api/v1/courses/{id}/registration}. */
	public record RegistrationRequest(@NotNull Boolean enabled) {

	}

	/** {@code PUT /api/v1/courses/order}: tenant course ids (any subset) in their new relative order. */
	public record CourseOrderRequest(@NotEmpty @Size(max = 1000) List<@NotNull UUID> courseIds) {

	}

	/** {@code PUT /api/v1/courses/{courseId}/modules/order}: every module of the course, once. */
	public record ModuleOrderRequest(@NotEmpty List<@NotNull UUID> moduleIds) {

	}

	/** {@code PUT .../modules/{moduleId}/lessons/order}: every lesson of the module, once. */
	public record LessonOrderRequest(@NotEmpty List<@NotNull UUID> lessonIds) {

	}

	/** {@code GET /api/v1/course-tags} item. */
	public record CourseTagResponse(UUID id, String name) {

	}

	/** {@code GET /api/v1/courses/lesson-resource-types} item. */
	public record LessonResourceTypeResponse(String type, String label, String support, String help,
			boolean liveClass) {

	}

}
