package com.lms.coursemanagement.course.web;

import com.lms.common.api.ApiResponse;
import com.lms.coursemanagement.course.service.CourseModuleService;
import com.lms.coursemanagement.course.service.CourseModuleView;
import com.lms.coursemanagement.course.web.dto.CourseModuleRequest;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
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
 * Course module ("structure only") endpoints, nested under a course. Stays
 * thin, delegates entirely to {@link CourseModuleService}, which performs
 * the real authorization check (loading the parent course first).
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/modules")
public class CourseModuleController {

	private final CourseModuleService courseModuleService;

	public CourseModuleController(CourseModuleService courseModuleService) {
		this.courseModuleService = courseModuleService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<CourseModuleResponse>>> listModules(@PathVariable UUID courseId) {
		List<CourseModuleResponse> modules = courseModuleService.listModules(courseId)
			.stream()
			.map(CourseModuleController::toResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(modules));
	}

	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseModuleResponse>> createModule(@PathVariable UUID courseId,
			@Valid @RequestBody CourseModuleRequest request) {
		CourseModuleView view = courseModuleService.createModule(courseId, request.title(), request.sequence());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	@PatchMapping("/{moduleId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseModuleResponse>> updateModule(@PathVariable UUID courseId,
			@PathVariable UUID moduleId, @Valid @RequestBody CourseModuleRequest request) {
		CourseModuleView view = courseModuleService.updateModule(courseId, moduleId, request.title(),
				request.sequence());
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@DeleteMapping("/{moduleId}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> deleteModule(@PathVariable UUID courseId, @PathVariable UUID moduleId) {
		courseModuleService.deleteModule(courseId, moduleId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	private static CourseModuleResponse toResponse(CourseModuleView view) {
		return new CourseModuleResponse(view.id(), view.courseId(), view.title(), view.sequence(), view.createdAt(),
				view.updatedAt());
	}

}
