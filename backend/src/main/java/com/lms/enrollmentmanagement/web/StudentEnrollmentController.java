package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.enrollmentmanagement.service.EnrollmentHistoryEntryView;
import com.lms.enrollmentmanagement.service.EnrollmentQueryService;
import com.lms.enrollmentmanagement.web.dto.EnrollmentHistoryEntryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 3 staff-facing, studentId-scoped enrollment history read ({@code GET
 * /api/v1/students/{id}/enrollments}) - lives in {@code
 * enrollment-management} (the owning domain of {@code enrollment}), not
 * duplicated into {@code user-management}, per the wave-03 plan §4. {@code
 * id} is the {@code StudentProfile}'s own resource id (matching every other
 * {@code /api/v1/students/{id}/...} URL) - resolved to the opaque
 * cross-domain {@code studentId} via {@code StudentLookupApi} inside {@link
 * EnrollmentQueryService#listForStudent}, never a client-supplied {@code
 * tenant_user} id.
 */
@RestController
@RequestMapping("/api/v1/students")
public class StudentEnrollmentController {

	private final EnrollmentQueryService enrollmentQueryService;

	public StudentEnrollmentController(EnrollmentQueryService enrollmentQueryService) {
		this.enrollmentQueryService = enrollmentQueryService;
	}

	@GetMapping("/{id}/enrollments")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<EnrollmentHistoryEntryResponse>>> getEnrollmentsForStudent(
			@PathVariable UUID id) {
		List<EnrollmentHistoryEntryResponse> response = enrollmentQueryService.listForStudent(id)
			.stream()
			.map(StudentEnrollmentController::toResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	private static EnrollmentHistoryEntryResponse toResponse(EnrollmentHistoryEntryView view) {
		return new EnrollmentHistoryEntryResponse(view.enrollmentId(), view.courseId(), view.current(),
				view.activatedAt(), view.accessExpiresAt(), view.supersededAt(), view.revokedAt(),
				view.revokeReason(), view.reactivatedFromEnrollmentId());
	}

}
