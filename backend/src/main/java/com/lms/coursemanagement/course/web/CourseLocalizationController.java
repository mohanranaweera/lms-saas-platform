package com.lms.coursemanagement.course.web;

import com.lms.common.api.ApiResponse;
import com.lms.coursemanagement.course.service.CourseLocalizationService;
import com.lms.coursemanagement.course.service.CourseLocalizationService.VariantView;
import com.lms.coursemanagement.course.service.CourseLocalizationService.VariantsView;
import com.lms.coursemanagement.course.web.dto.CourseLocalizationRequest;
import com.lms.coursemanagement.course.web.dto.CourseLocalizationVersionRequest;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * I18N-01 section 8: course editor -> Translations. Gated exactly like editing the course
 * ({@code COURSES_EDIT}, then {@code CourseAccessGuard} CREATE_EDIT in the service). Working
 * copies (drafts) are only visible here, never on the storefront.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}/localizations")
public class CourseLocalizationController {

	private final CourseLocalizationService localizationService;

	public CourseLocalizationController(CourseLocalizationService localizationService) {
		this.localizationService = localizationService;
	}

	private static <T> ResponseEntity<ApiResponse<T>> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(body));
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.COURSES_EDIT)
	public ResponseEntity<ApiResponse<VariantsView>> list(@PathVariable UUID courseId) {
		return ok(localizationService.list(courseId));
	}

	@GetMapping("/{locale}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.COURSES_EDIT)
	public ResponseEntity<ApiResponse<VariantView>> get(@PathVariable UUID courseId, @PathVariable String locale) {
		return ok(localizationService.get(courseId, locale));
	}

	@PutMapping("/{locale}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.COURSES_EDIT)
	public ResponseEntity<ApiResponse<VariantView>> save(@PathVariable UUID courseId, @PathVariable String locale,
			@RequestBody CourseLocalizationRequest request) {
		return ok(localizationService.save(courseId, locale, request.title(), request.description(),
				request.expectedVersion()));
	}

	@PostMapping("/{locale}/publish")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.COURSES_EDIT)
	public ResponseEntity<ApiResponse<VariantView>> publish(@PathVariable UUID courseId, @PathVariable String locale,
			@RequestBody(required = false) CourseLocalizationVersionRequest request) {
		return ok(localizationService.publish(courseId, locale, request == null ? null : request.expectedVersion()));
	}

	@PostMapping("/{locale}/unpublish")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.COURSES_EDIT)
	public ResponseEntity<ApiResponse<VariantView>> unpublish(@PathVariable UUID courseId,
			@PathVariable String locale, @RequestBody(required = false) CourseLocalizationVersionRequest request) {
		return ok(localizationService.unpublish(courseId, locale, request == null ? null : request.expectedVersion()));
	}

}
