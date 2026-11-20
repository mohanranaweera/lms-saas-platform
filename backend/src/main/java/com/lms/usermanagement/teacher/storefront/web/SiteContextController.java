package com.lms.usermanagement.teacher.storefront.web;

import com.lms.common.api.ApiResponse;
import com.lms.usermanagement.teacher.storefront.service.StorefrontSiteContextService;
import com.lms.usermanagement.teacher.storefront.web.dto.StorefrontDtos.SiteContextResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STORE-01: anonymous {@code GET /api/v1/public/site-context} (permitAll in
 * {@code SecurityFilterChainConfig}). Tenant and storefront both come from
 * the host via {@code TenantResolutionFilter}; no parameters are accepted.
 */
@RestController
public class SiteContextController {

	private final StorefrontSiteContextService siteContextService;

	public SiteContextController(StorefrontSiteContextService siteContextService) {
		this.siteContextService = siteContextService;
	}

	@GetMapping("/api/v1/public/site-context")
	public ResponseEntity<ApiResponse<SiteContextResponse>> siteContext() {
		return ResponseEntity.ok(ApiResponse.success(siteContextService.siteContext()));
	}

}
