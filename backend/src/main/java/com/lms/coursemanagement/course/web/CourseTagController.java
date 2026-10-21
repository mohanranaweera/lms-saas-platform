package com.lms.coursemanagement.course.web;

import com.lms.common.api.ApiResponse;
import com.lms.coursemanagement.course.service.CourseRelationsService;
import com.lms.coursemanagement.course.web.dto.CourseKlassRequests.CourseTagResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * KLASS-T4a: the tenant's course tags (admin tag selector, student catalog
 * chips). Any authenticated user of the resolved tenant; tags are
 * tenant-scoped by the repository, never by a request parameter.
 */
@RestController
@RequestMapping("/api/v1/course-tags")
public class CourseTagController {

	private final CourseRelationsService relationsService;

	public CourseTagController(CourseRelationsService relationsService) {
		this.relationsService = relationsService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<List<CourseTagResponse>>> listTags() {
		List<CourseTagResponse> tags = relationsService.listTenantTags()
			.stream()
			.map(tag -> new CourseTagResponse(tag.getId(), tag.getName()))
			.toList();
		return ResponseEntity.ok(ApiResponse.success(tags));
	}

}
