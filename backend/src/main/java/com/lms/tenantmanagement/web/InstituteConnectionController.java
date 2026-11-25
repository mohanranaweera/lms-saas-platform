package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.tenant.TenantContext;
import com.lms.tenantmanagement.service.InstituteConnectionService;
import com.lms.tenantmanagement.service.InstituteConnectionService.InstituteConnection;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * INST-QR-01: the signed-in institute's own Institute QR, for the Tenant Admin
 * dashboard card. The tenant is the one resolved from the site's host and the
 * caller's token - there is no tenant parameter. Same read permission as the
 * institute settings it is derived from (Tenant Admin, Read-only Auditor).
 */
@RestController
@RequestMapping("/api/v1/tenant-config/institute-connection")
public class InstituteConnectionController {

	private final InstituteConnectionService service;

	private final TenantContext tenantContext;

	public InstituteConnectionController(InstituteConnectionService service, TenantContext tenantContext) {
		this.service = service;
		this.tenantContext = tenantContext;
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'VIEW')")
	public ResponseEntity<ApiResponse<InstituteConnection>> get() {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.forTenant(tenantContext.getTenantId())));
	}

}
