package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.enrollmentmanagement.service.EnrollmentQueryService;
import com.lms.enrollmentmanagement.service.EnrollmentSummaryView;
import com.lms.enrollmentmanagement.web.dto.CourseSummaryResponse;
import com.lms.enrollmentmanagement.web.dto.EnrollmentSummaryResponse;
import com.lms.enrollmentmanagement.web.dto.RevokeEnrollmentRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/enrollments/my} (plan §10) - owner-only by construction, no id param. */
@RestController
@RequestMapping("/api/v1/enrollments")
public class EnrollmentController {

	private final EnrollmentQueryService enrollmentQueryService;

	private final EnrollmentActivationApi enrollmentActivationApi;

	public EnrollmentController(EnrollmentQueryService enrollmentQueryService,
			EnrollmentActivationApi enrollmentActivationApi) {
		this.enrollmentQueryService = enrollmentQueryService;
		this.enrollmentActivationApi = enrollmentActivationApi;
	}

	/**
	 * Wave 3 (Student actions - staff "revoke enrollment"). Permission is
	 * enforced inside {@code EnrollmentActivationService#revoke} (defense in
	 * depth, matching this codebase's established pattern) - the
	 * {@code @PreAuthorize} here is a coarse authenticated-only gate.
	 */
	@PostMapping("/{id}/revoke")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable UUID id,
			@Valid @RequestBody RevokeEnrollmentRequest request) {
		enrollmentActivationApi.revoke(id, request.reason());
		return ResponseEntity.ok(ApiResponse.success(null));
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
