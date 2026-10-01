package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.enrollmentmanagement.service.AccessPolicyAdminService;
import com.lms.enrollmentmanagement.service.AccessPolicyAdminService.CourseAccessPolicyView;
import com.lms.enrollmentmanagement.service.AccessPolicyAdminService.TenantAccessPolicyView;
import com.lms.enrollmentmanagement.web.dto.AccessPolicyRequests.CourseAccessPolicyRequest;
import com.lms.enrollmentmanagement.web.dto.AccessPolicyRequests.TenantAccessPolicyRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 10 - tenant grace period and per-course access overrides. {@code
 * ACCESS_EXPIRY} V / C/E (course device limits additionally need {@code
 * DEVICES} C/E, checked in the service). Thin - logic in {@link
 * AccessPolicyAdminService}.
 */
@RestController
@RequestMapping("/api/v1/access-policy")
public class AccessPolicyController {

	private final AccessPolicyAdminService accessPolicyAdminService;

	public AccessPolicyController(AccessPolicyAdminService accessPolicyAdminService) {
		this.accessPolicyAdminService = accessPolicyAdminService;
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('ACCESS_EXPIRY', 'VIEW')")
	public ResponseEntity<ApiResponse<TenantAccessPolicyView>> getTenantPolicy() {
		return ResponseEntity.ok(ApiResponse.success(accessPolicyAdminService.getTenantPolicy()));
	}

	@PutMapping
	@PreAuthorize("@permissionCheckService.hasPermission('ACCESS_EXPIRY', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<TenantAccessPolicyView>> updateTenantPolicy(
			@Valid @RequestBody TenantAccessPolicyRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(accessPolicyAdminService.updateTenantPolicy(request.gracePeriodDays())));
	}

	@GetMapping("/courses")
	@PreAuthorize("@permissionCheckService.hasPermission('ACCESS_EXPIRY', 'VIEW')")
	public ResponseEntity<ApiResponse<List<CourseAccessPolicyView>>> listCoursePolicies() {
		return ResponseEntity.ok(ApiResponse.success(accessPolicyAdminService.listCoursePolicies()));
	}

	/** Returns the saved override, or {@code null} data when both values were cleared. */
	@PutMapping("/courses/{courseId}")
	@PreAuthorize("@permissionCheckService.hasPermission('ACCESS_EXPIRY', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<CourseAccessPolicyView>> upsertCoursePolicy(@PathVariable UUID courseId,
			@Valid @RequestBody CourseAccessPolicyRequest request) {
		return ResponseEntity.ok(ApiResponse.success(accessPolicyAdminService
			.upsertCoursePolicy(courseId, request.gracePeriodDays(), request.maxDevices())
			.orElse(null)));
	}

}
