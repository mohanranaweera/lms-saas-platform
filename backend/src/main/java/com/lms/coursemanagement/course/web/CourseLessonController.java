package com.lms.coursemanagement.course.web;

import com.lms.common.api.ApiResponse;
import com.lms.coursemanagement.course.service.CourseLessonService;
import com.lms.coursemanagement.course.service.CourseLessonView;
import com.lms.coursemanagement.course.web.dto.CourseLessonRequest;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Course lesson ("structure only") endpoints, nested under a course and
 * module. Stays thin, delegates entirely to {@link CourseLessonService}.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/modules/{moduleId}/lessons")
public class CourseLessonController {

	private final CourseLessonService courseLessonService;

	public CourseLessonController(CourseLessonService courseLessonService) {
		this.courseLessonService = courseLessonService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<CourseLessonResponse>>> listLessons(@PathVariable UUID courseId,
			@PathVariable UUID moduleId) {
		List<CourseLessonResponse> lessons = courseLessonService.listLessons(courseId, moduleId)
			.stream()
			.map(CourseLessonController::toResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(lessons));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseLessonResponse>> createLesson(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @Valid @RequestBody CourseLessonRequest request) {
		CourseLessonView view = courseLessonService.createLesson(courseId, moduleId, request.title(),
				request.sequence());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	@PatchMapping("/{lessonId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseLessonResponse>> updateLesson(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @PathVariable UUID lessonId, @Valid @RequestBody CourseLessonRequest request) {
		CourseLessonView view = courseLessonService.updateLesson(courseId, moduleId, lessonId, request.title(),
				request.sequence());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@DeleteMapping("/{lessonId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> deleteLesson(@PathVariable UUID courseId, @PathVariable UUID moduleId,
			@PathVariable UUID lessonId) {
		courseLessonService.deleteLesson(courseId, moduleId, lessonId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	private static CourseLessonResponse toResponse(CourseLessonView view) {
		return new CourseLessonResponse(view.id(), view.moduleId(), view.title(), view.sequence(), view.createdAt(),
				view.updatedAt());
	}

}
