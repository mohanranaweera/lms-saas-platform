package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.enrollmentmanagement.service.EnrollmentQueryService;
import com.lms.enrollmentmanagement.web.dto.EnrollmentAccessStateResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/courses/{courseId}/access-state} (plan §10) - see
 * {@link EnrollmentQueryService}'s javadoc for why this always resolves the
 * calling student's own access state.
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseAccessStateController {

	private final EnrollmentQueryService enrollmentQueryService;

	public CourseAccessStateController(EnrollmentQueryService enrollmentQueryService) {
		this.enrollmentQueryService = enrollmentQueryService;
	}

	@GetMapping("/{courseId}/access-state")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<EnrollmentAccessStateResponse>> getAccessState(@PathVariable UUID courseId) {
		EnrollmentAccessState state = enrollmentQueryService.getMyAccessState(courseId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(state)));
	}

	private static EnrollmentAccessStateResponse toResponse(EnrollmentAccessState state) {
		return new EnrollmentAccessStateResponse(state.state(), state.enrollmentId(), state.accessExpiresAt(),
				state.canRequestReactivation());
	}

}
