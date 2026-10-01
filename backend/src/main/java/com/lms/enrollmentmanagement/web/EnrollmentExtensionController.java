package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.enrollmentmanagement.service.AccessExtensionService;
import com.lms.enrollmentmanagement.service.AccessExtensionService.AccessExtensionView;
import com.lms.enrollmentmanagement.web.dto.AccessPolicyRequests.AccessExtensionRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
 * Wave 10 - manual access extensions of one enrollment (ADR-022). {@code
 * ACCESS_EXPIRY} V to list, C/E (Tenant Admin) to extend. Thin - rules in
 * {@link AccessExtensionService}.
 */
@RestController
@RequestMapping("/api/v1/enrollments/{enrollmentId}/extensions")
public class EnrollmentExtensionController {

	private final AccessExtensionService accessExtensionService;

	public EnrollmentExtensionController(AccessExtensionService accessExtensionService) {
		this.accessExtensionService = accessExtensionService;
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('ACCESS_EXPIRY', 'VIEW')")
	public ResponseEntity<ApiResponse<List<AccessExtensionView>>> listExtensions(@PathVariable UUID enrollmentId) {
		return ResponseEntity.ok(ApiResponse.success(accessExtensionService.listExtensions(enrollmentId)));
	}

	@PostMapping
	@PreAuthorize("@permissionCheckService.hasPermission('ACCESS_EXPIRY', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<AccessExtensionView>> extend(@PathVariable UUID enrollmentId,
			@Valid @RequestBody AccessExtensionRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(
					accessExtensionService.extend(enrollmentId, request.newExpiresAt(), request.reason())));
	}

}
