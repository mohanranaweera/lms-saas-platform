package com.lms.coursemanagement.course.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.service.BillingConfigurationService;
import com.lms.coursemanagement.course.service.CourseBillingConfigurationView;
import com.lms.coursemanagement.course.service.CourseBillingPeriodView;
import com.lms.coursemanagement.course.web.dto.CourseBillingConfigurationRequest;
import com.lms.coursemanagement.course.web.dto.CourseBillingConfigurationResponse;
import com.lms.coursemanagement.course.web.dto.CourseBillingPeriodRequest;
import com.lms.coursemanagement.course.web.dto.CourseBillingPeriodResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 2's course billing-configuration/billing-period endpoints. Stays
 * thin, delegates entirely to {@link BillingConfigurationService}, which
 * performs the real, combined staff-matrix-or-Teacher-ownership authorization
 * check per method (via {@code CourseAccessGuard}) - {@code
 * @PreAuthorize("isAuthenticated()")} here is a coarse gate only, matching
 * {@code CourseController}'s established convention exactly.
 */
@RestController
@RequestMapping("/api/v1/courses/{courseId}")
public class CourseBillingController {

	private final BillingConfigurationService billingConfigurationService;

	public CourseBillingController(BillingConfigurationService billingConfigurationService) {
		this.billingConfigurationService = billingConfigurationService;
	}

	@GetMapping("/billing-configuration")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseBillingConfigurationResponse>> getConfiguration(
			@PathVariable UUID courseId) {
		CourseBillingConfigurationView view = billingConfigurationService.getConfiguration(courseId);
		return ResponseEntity.ok(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/billing-configuration")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseBillingConfigurationResponse>> createOrUpdateConfiguration(
			@PathVariable UUID courseId, @Valid @RequestBody CourseBillingConfigurationRequest request) {
		CourseBillingConfigurationView view = billingConfigurationService.createOrUpdateConfiguration(courseId,
				request.sessionRate(), request.currency(), request.requiresManualQuote());
		return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success(toResponse(view)));
	}

	@GetMapping("/billing-periods")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<CourseBillingPeriodResponse>>> listPeriods(
			@PathVariable UUID courseId,
			@PageableDefault(size = 20, sort = "effectiveFrom", direction = Sort.Direction.DESC) Pageable pageable) {
		Page<CourseBillingPeriodView> page = billingConfigurationService.getPeriodHistory(courseId, pageable);
		return ResponseEntity.ok(ApiResponse.success(PageResponse.from(page.map(CourseBillingController::toResponse))));
	}

	@PostMapping("/billing-periods")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<CourseBillingPeriodResponse>> addPeriod(@PathVariable UUID courseId,
			@Valid @RequestBody CourseBillingPeriodRequest request) {
		Instant effectiveFrom = request.effectiveFrom() != null ? request.effectiveFrom() : Instant.now();
		CourseBillingPeriodView view = billingConfigurationService.addBillingPeriod(courseId, request.amount(),
				request.currency(), effectiveFrom);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	private static CourseBillingConfigurationResponse toResponse(CourseBillingConfigurationView view) {
		return new CourseBillingConfigurationResponse(view.id(), view.courseId(), view.sessionRate(),
				view.currency(), view.requiresManualQuote(), view.createdAt(), view.updatedAt());
	}

	private static CourseBillingPeriodResponse toResponse(CourseBillingPeriodView view) {
		return new CourseBillingPeriodResponse(view.id(), view.billingConfigurationId(), view.amount(),
				view.currency(), view.effectiveFrom(), view.effectiveTo(), view.createdAt());
	}

}
