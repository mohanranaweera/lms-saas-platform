package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.api.CourseCatalogApi.CatalogEntry;
import com.lms.coursemanagement.api.CourseCatalogApi.CatalogSort;
import com.lms.enrollmentmanagement.service.CourseParticipationService;
import com.lms.enrollmentmanagement.service.CourseParticipationService.CatalogItem;
import com.lms.enrollmentmanagement.service.CourseParticipationService.CatalogView;
import com.lms.enrollmentmanagement.service.CourseParticipationService.SelfEnrollResult;
import com.lms.enrollmentmanagement.web.dto.StudentCatalogResponses.CatalogCourseResponse;
import com.lms.enrollmentmanagement.web.dto.StudentCatalogResponses.PriceDisplay;
import com.lms.enrollmentmanagement.web.dto.StudentCatalogResponses.SelfEnrollResponse;
import com.lms.enrollmentmanagement.web.dto.StudentCatalogResponses.TagResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * KLASS-T4b (plan §4.2, EP-E01..E05, ADR-028): free enrollment and the student
 * catalog. Student id = authenticated principal, tenant = resolved tenant
 * context; no request field names either.
 */
@RestController
@RequestMapping("/api/v1/enrollments/my")
public class CourseParticipationController {

	private final CourseParticipationService participationService;

	public CourseParticipationController(CourseParticipationService participationService) {
		this.participationService = participationService;
	}

	/** Idempotent; 404 not visible; 409 {@code REGISTRATION_CLOSED}. Never creates an order or payment. */
	@PostMapping("/courses/{courseId}")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<SelfEnrollResponse>> enroll(@PathVariable UUID courseId) {
		SelfEnrollResult result = participationService.selfEnroll(courseId);
		return ResponseEntity
			.ok(ApiResponse.success(new SelfEnrollResponse(result.courseId(), result.enrolledAt(), result.created())));
	}

	@GetMapping("/catalog")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PageResponse<CatalogCourseResponse>>> catalog(
			@RequestParam(defaultValue = "AVAILABLE") CatalogView view,
			@RequestParam(required = false) String search, @RequestParam(required = false) UUID tagId,
			@RequestParam(defaultValue = "DEFAULT") CatalogSort sort, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		PageResponse<CatalogItem> items = participationService.catalog(view, search, tagId, sort, page, size);
		return ResponseEntity.ok(ApiResponse.success(new PageResponse<>(
				items.content().stream().map(CourseParticipationController::toResponse).toList(), items.page(),
				items.size(), items.totalElements(), items.totalPages())));
	}

	private static CatalogCourseResponse toResponse(CatalogItem item) {
		CatalogEntry entry = item.entry();
		PriceDisplay price = entry.priceAmount() == null ? null
				: new PriceDisplay(entry.priceAmount(), entry.priceCurrency(), entry.pricePeriod());
		return new CatalogCourseResponse(entry.courseId(), entry.title(), entry.teacherName(), entry.coverImageUrl(),
				price, entry.pricingModel(), entry.tags().stream().map(t -> new TagResponse(t.id(), t.name())).toList(),
				item.enrolled(), entry.registrationEnabled());
	}

}
