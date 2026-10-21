package com.lms.integrationmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.integrationmanagement.service.PlatformVideoProviderService;
import com.lms.integrationmanagement.service.PlatformVideoProviderService.PlatformVideoProviderView;
import com.lms.integrationmanagement.web.dto.GatewayDisableRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform Admin switch-off/on of a tenant's YouTube channel for uploads
 * (VIDEO Phase 4, TD-008). The reason is required to switch off; credentials
 * are never readable here.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/tenants/{tenantId}/video-providers")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformAdminVideoProviderController {

	private final PlatformVideoProviderService service;

	public PlatformAdminVideoProviderController(PlatformVideoProviderService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<List<PlatformVideoProviderView>>> list(@PathVariable UUID tenantId) {
		return ResponseEntity.ok(ApiResponse.success(service.list(tenantId)));
	}

	@PostMapping("/{accountId}/disable")
	public ResponseEntity<ApiResponse<PlatformVideoProviderView>> disable(@PathVariable UUID tenantId,
			@PathVariable UUID accountId, @Valid @RequestBody GatewayDisableRequest request) {
		return ResponseEntity.ok(ApiResponse.success(service.disable(tenantId, accountId, request.reason())));
	}

	@PostMapping("/{accountId}/enable")
	public ResponseEntity<ApiResponse<PlatformVideoProviderView>> enable(@PathVariable UUID tenantId,
			@PathVariable UUID accountId) {
		return ResponseEntity.ok(ApiResponse.success(service.enable(tenantId, accountId)));
	}

}
