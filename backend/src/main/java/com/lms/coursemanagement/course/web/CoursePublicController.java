package com.lms.coursemanagement.course.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.service.CoursePublicService;
import com.lms.coursemanagement.course.service.PublicCourseView;
import com.lms.coursemanagement.course.web.dto.PublicCourseResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous public storefront read path (plan §9/§10/§15(d)). No {@code
 * @PreAuthorize} here - these two GET routes are on {@code
 * SecurityFilterChainConfig}'s {@code permitAll()} list, mirroring the
 * existing {@code POST /api/v1/tenant-registrations} precedent. Tenant
 * identity is still resolved server-side, from the subdomain, by {@code
 * TenantResolutionFilter} - never a client-supplied {@code tenantId} query
 * param, and none is accepted here.
 */
@RestController
@RequestMapping("/api/v1/public/courses")
public class CoursePublicController {

	private final CoursePublicService coursePublicService;

	public CoursePublicController(CoursePublicService coursePublicService) {
		this.coursePublicService = coursePublicService;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<PublicCourseResponse>>> listCourses(
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		PageResponse<PublicCourseView> page = coursePublicService.listPublishedCourses(pageable);
		PageResponse<PublicCourseResponse> response = new PageResponse<>(
				page.content().stream().map(CoursePublicController::toResponse).toList(), page.page(), page.size(),
				page.totalElements(), page.totalPages());
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/{slug}")
	public ResponseEntity<ApiResponse<PublicCourseResponse>> getCourse(@PathVariable String slug) {
		PublicCourseView view = coursePublicService.getPublishedCourseBySlug(slug);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	private static PublicCourseResponse toResponse(PublicCourseView view) {
		return new PublicCourseResponse(view.id(), view.name(), view.slug(), view.category(), view.subject(),
				view.stream(), view.grade(), view.academicYear(), view.description(), view.price(),
				view.accessDurationDays(), view.enrollmentRule());
	}

}
