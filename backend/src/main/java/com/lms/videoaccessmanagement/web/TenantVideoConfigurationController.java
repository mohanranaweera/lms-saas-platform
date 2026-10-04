package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.videoaccessmanagement.service.TenantVideoConfigurationService;
import com.lms.videoaccessmanagement.service.TenantVideoConfigurationService.TenantVideoConfigurationView;
import com.lms.videoaccessmanagement.web.dto.TenantVideoConfigurationRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Admin → Settings → Video (VIDEO Phase 3). Thin - the {@code
 * INTEGRATIONS} permission check and tenant scoping live in {@link
 * TenantVideoConfigurationService}.
 */
@RestController
@RequestMapping("/api/v1/tenant-admin/video-configuration")
@PreAuthorize("isAuthenticated()")
public class TenantVideoConfigurationController {

	private final TenantVideoConfigurationService service;

	public TenantVideoConfigurationController(TenantVideoConfigurationService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<TenantVideoConfigurationView>> get() {
		return ResponseEntity.ok(ApiResponse.success(service.get()));
	}

	@PutMapping
	public ResponseEntity<ApiResponse<TenantVideoConfigurationView>> update(
			@Valid @RequestBody TenantVideoConfigurationRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.update(request.toSettings())));
	}

}
