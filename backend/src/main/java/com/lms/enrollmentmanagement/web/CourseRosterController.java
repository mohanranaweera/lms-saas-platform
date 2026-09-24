package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.enrollmentmanagement.service.CourseRosterEntryView;
import com.lms.enrollmentmanagement.service.CourseRosterService;
import com.lms.enrollmentmanagement.web.dto.CourseRosterEntryResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 3 (PAR-03-06/PAR-04-03) - a real, backend-filtered course roster for
 * Teacher (own-course-only) or staff. See {@link CourseRosterService} for the
 * full authorization contract.
 */
@RestController
@RequestMapping("/api/v1/courses")
public class CourseRosterController {

	private final CourseRosterService courseRosterService;

	public CourseRosterController(CourseRosterService courseRosterService) {
		this.courseRosterService = courseRosterService;
	}

	@GetMapping("/{courseId}/roster")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<CourseRosterEntryResponse>>> getRoster(@PathVariable UUID courseId) {
		List<CourseRosterEntryResponse> response = courseRosterService.getRoster(courseId)
			.stream()
			.map(v -> new CourseRosterEntryResponse(v.studentProfileId(), v.userId(), v.name(), v.email()))
			.toList();
		return ResponseEntity.ok(ApiResponse.success(response));
	}

}
