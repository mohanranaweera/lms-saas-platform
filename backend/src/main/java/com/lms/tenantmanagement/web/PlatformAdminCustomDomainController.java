package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.tenantmanagement.service.CustomDomainService;
import com.lms.tenantmanagement.web.dto.CustomDomainResponse;
import com.lms.tenantmanagement.web.dto.CustomDomainsResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
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
 * TEN-DOM-01 / ADR-021 decision 5: Platform Admin control of a tenant's custom
 * domains - see them, switch the feature on/off, suspend a domain.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/tenants/{tenantId}")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformAdminCustomDomainController {

	private final CustomDomainService customDomainService;

	public PlatformAdminCustomDomainController(CustomDomainService customDomainService) {
		this.customDomainService = customDomainService;
	}

	@GetMapping("/custom-domains")
	public ResponseEntity<ApiResponse<CustomDomainsResponse>> list(@PathVariable UUID tenantId) {
		return ResponseEntity
			.ok(ApiResponse.success(CustomDomainsResponse.from(customDomainService.overviewForTenant(tenantId))));
	}

	@PutMapping("/custom-domains-enabled")
	public ResponseEntity<ApiResponse<CustomDomainsResponse>> setEnabled(@PathVariable UUID tenantId,
			@Valid @RequestBody EnabledRequest request) {
		return ResponseEntity.ok(ApiResponse
			.success(CustomDomainsResponse.from(customDomainService.setEnabled(tenantId, request.enabled()))));
	}

	@PostMapping("/custom-domains/{domainId}/suspend")
	public ResponseEntity<ApiResponse<CustomDomainResponse>> suspend(@PathVariable UUID tenantId,
			@PathVariable UUID domainId, @Valid @RequestBody SuspendRequest request) {
		return ResponseEntity.ok(ApiResponse
			.success(CustomDomainResponse.from(customDomainService.suspend(tenantId, domainId, request.reason()))));
	}

	public record EnabledRequest(@NotNull Boolean enabled) {

	}

	public record SuspendRequest(@NotBlank @Size(max = 500) String reason) {

	}

}
