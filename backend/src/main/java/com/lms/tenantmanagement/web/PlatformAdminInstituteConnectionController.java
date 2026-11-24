package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.tenantmanagement.service.InstituteConnectionService;
import com.lms.tenantmanagement.service.InstituteConnectionService.InstituteConnection;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * INST-QR-01: a selected institute's Institute QR on the Platform Admin tenant
 * details page. The response echoes {@code tenantId} so the page can refuse to show
 * a QR that doesn't belong to the tenant it is displaying.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/tenants/{tenantId}/institute-connection")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class PlatformAdminInstituteConnectionController {

	private final InstituteConnectionService service;

	public PlatformAdminInstituteConnectionController(InstituteConnectionService service) {
		this.service = service;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<InstituteConnection>> get(@PathVariable UUID tenantId) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(service.forTenant(tenantId)));
	}

}
