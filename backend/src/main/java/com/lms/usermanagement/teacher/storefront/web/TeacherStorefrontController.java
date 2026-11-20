package com.lms.usermanagement.teacher.storefront.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.usermanagement.teacher.storefront.service.TeacherStorefrontService;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.StorefrontAdminResponse;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.StorefrontProductsRequest;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.StorefrontUpdateRequest;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.TeacherStorefrontRowResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * STORE-01 / ADR-037 storefront administration (plan section 6). Thin: the
 * service re-checks {@code TEACHER_STOREFRONT_MANAGE}, validates and audits.
 * {@code teacherId} is the teacher's {@code tenant_user.id}.
 */
@RestController
@RequestMapping("/api/v1/teacher-storefronts")
public class TeacherStorefrontController {

	private final TeacherStorefrontService storefrontService;

	public TeacherStorefrontController(TeacherStorefrontService storefrontService) {
		this.storefrontService = storefrontService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHER_STOREFRONT_MANAGE)
	public ResponseEntity<ApiResponse<PageResponse<TeacherStorefrontRowResponse>>> list(
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.success(storefrontService.list(page, size)));
	}

	/** The signed-in Teacher's own storefront (read-only); 404 when none exists. */
	@GetMapping("/me")
	@PreAuthorize("hasRole('TEACHER')")
	public ResponseEntity<ApiResponse<StorefrontAdminResponse>> own() {
		return ResponseEntity.ok(ApiResponse.success(storefrontService.getOwn()));
	}

	@GetMapping("/{teacherId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHER_STOREFRONT_MANAGE)
	public ResponseEntity<ApiResponse<StorefrontAdminResponse>> get(@PathVariable UUID teacherId) {
		return ResponseEntity.ok(ApiResponse.success(storefrontService.get(teacherId)));
	}

	@PutMapping("/{teacherId}")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHER_STOREFRONT_MANAGE)
	public ResponseEntity<ApiResponse<StorefrontAdminResponse>> upsert(@PathVariable UUID teacherId,
			@RequestBody StorefrontUpdateRequest request) {
		return ResponseEntity.ok(ApiResponse.success(storefrontService.upsert(teacherId, request)));
	}

	@PutMapping("/{teacherId}/products")
	@PreAuthorize("isAuthenticated()")
	@RequiresCapability(Capability.TEACHER_STOREFRONT_MANAGE)
	public ResponseEntity<ApiResponse<StorefrontAdminResponse>> replaceProducts(@PathVariable UUID teacherId,
			@RequestBody StorefrontProductsRequest request) {
		return ResponseEntity.ok(ApiResponse.success(storefrontService.replaceProducts(teacherId, request)));
	}

}
