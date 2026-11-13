package com.lms.coursemanagement.course.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.service.CourseLocalizationService;
import com.lms.coursemanagement.course.service.CoursePublicService;
import com.lms.coursemanagement.course.service.PublicCourseView;
import com.lms.coursemanagement.course.web.dto.PublicCourseResponse;
import com.lms.coursemanagement.course.web.dto.PublicCourseTagResponse;
import com.lms.localizationmanagement.api.LocaleInputs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
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

	private final CourseLocalizationService courseLocalizationService;

	public CoursePublicController(CoursePublicService coursePublicService,
			CourseLocalizationService courseLocalizationService) {
		this.coursePublicService = coursePublicService;
		this.courseLocalizationService = courseLocalizationService;
	}

	@GetMapping("/tags")
	public ResponseEntity<ApiResponse<List<PublicCourseTagResponse>>> listTags() {
		return ResponseEntity.ok(ApiResponse.success(coursePublicService.listPublicTags().stream()
			.map(tag -> new PublicCourseTagResponse(tag.id(), tag.name())).toList()));
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PageResponse<PublicCourseResponse>>> listCourses(
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
			@org.springframework.web.bind.annotation.RequestParam(required = false) java.util.UUID tagId,
			HttpServletRequest request) {
		PageResponse<PublicCourseView> page = coursePublicService.listPublishedCourses(pageable, tagId);
		// I18N-01: PUBLISHED title/description variant for the request's resolved locale.
		List<PublicCourseView> localized = courseLocalizationService.localizePublic(page.content(),
				LocaleInputs.from(request));
		PageResponse<PublicCourseResponse> response = new PageResponse<>(
				localized.stream().map(CoursePublicController::toResponse).toList(), page.page(), page.size(),
				page.totalElements(), page.totalPages());
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	@GetMapping("/{slug}")
	public ResponseEntity<ApiResponse<PublicCourseResponse>> getCourse(@PathVariable String slug,
			HttpServletRequest request) {
		PublicCourseView view = coursePublicService.getPublishedCourseBySlug(slug);
		PublicCourseView localized = courseLocalizationService.localizePublic(List.of(view), LocaleInputs.from(request))
			.get(0);
		return ResponseEntity.ok(ApiResponse.success(toResponse(localized)));
	}

	private static PublicCourseResponse toResponse(PublicCourseView view) {
		return new PublicCourseResponse(view.id(), view.name(), view.slug(), view.category(), view.subject(),
				view.stream(), view.grade(), view.academicYear(), view.description(), view.price(),
				view.accessDurationDays(), view.enrollmentRule(), view.pricingModel(), view.resolvedAmount(),
				view.currency(), view.requiresManualQuote(), view.teacherName(), view.coverImageUrl(), view.registrationEnabled(), view.outline(), view.tags(),
				view.contentLocale(), view.availableLocales());
	}

}
