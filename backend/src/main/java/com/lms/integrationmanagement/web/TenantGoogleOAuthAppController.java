package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.TenantGoogleOAuthAppService;
import com.lms.integrationmanagement.service.TenantGoogleOAuthAppService.TenantGoogleOAuthAppView;
import com.lms.integrationmanagement.service.TenantGoogleOAuthAppService.VerifyView;
import com.lms.integrationmanagement.web.PlatformAdminOAuthAppController.GoogleOAuthAppRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Admin → Video providers → the institute's own Google app for its
 * YouTube channel (VIDEO Phase 5c). Permission checks, tenant scoping,
 * encryption and auditing live in {@link TenantGoogleOAuthAppService}.
 */
@RestController
@RequestMapping("/api/v1/tenant-admin/video-providers/youtube/oauth-app")
@PreAuthorize("isAuthenticated()")
public class TenantGoogleOAuthAppController {

	private final TenantGoogleOAuthAppService service;

	public TenantGoogleOAuthAppController(TenantGoogleOAuthAppService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<TenantGoogleOAuthAppView>> get() {
		return ResponseEntity.ok(ApiResponse.success(service.view()));
	}

	@PutMapping
	public ResponseEntity<ApiResponse<TenantGoogleOAuthAppView>> save(
			@Valid @RequestBody GoogleOAuthAppRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.save(request.clientId(), request.clientSecret())));
	}

	@DeleteMapping
	public ResponseEntity<ApiResponse<TenantGoogleOAuthAppView>> remove() {
		return ResponseEntity.ok(ApiResponse.success(service.remove()));
	}

	@PostMapping("/verify")
	public ResponseEntity<ApiResponse<VerifyView>> verify() {
		return ResponseEntity.ok(ApiResponse.success(service.verify()));
	}

}
