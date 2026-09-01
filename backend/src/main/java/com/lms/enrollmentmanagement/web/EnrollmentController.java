package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.enrollmentmanagement.service.EnrollmentQueryService;
import com.lms.enrollmentmanagement.service.EnrollmentSummaryView;
import com.lms.enrollmentmanagement.web.dto.CourseSummaryResponse;
import com.lms.enrollmentmanagement.web.dto.EnrollmentSummaryResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/enrollments/my} (plan §10) - owner-only by construction, no id param. */
@RestController
@RequestMapping("/api/v1/enrollments")
public class EnrollmentController {

	private final EnrollmentQueryService enrollmentQueryService;

	public EnrollmentController(EnrollmentQueryService enrollmentQueryService) {
		this.enrollmentQueryService = enrollmentQueryService;
	}

	@GetMapping("/my")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<List<EnrollmentSummaryResponse>>> myEnrollments() {
		List<EnrollmentSummaryResponse> response = enrollmentQueryService.listMyEnrollments()
			.stream()
			.map(EnrollmentController::toResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/my/courses")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<List<CourseSummaryResponse>>> myEnrolledCourseSummaries() {
		List<CourseSummaryResponse> response = enrollmentQueryService.listMyEnrolledCourseSummaries()
			.stream()
			.map(s -> new CourseSummaryResponse(s.id(), s.name(), s.slug(), s.category()))
			.toList();
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	private static EnrollmentSummaryResponse toResponse(EnrollmentSummaryView view) {
		return new EnrollmentSummaryResponse(view.enrollmentId(), view.courseId(), view.accessState().state(),
				view.accessState().accessExpiresAt(), view.accessState().canRequestReactivation());
	}

}
