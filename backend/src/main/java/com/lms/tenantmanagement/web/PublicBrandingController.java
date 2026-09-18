package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.tenant.TenantContext;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.web.dto.PublicBrandingResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The one deliberately unauthenticated tenant-configuration read (Wave 1).
 * No {@code @PreAuthorize} here, and this route is on {@code
 * SecurityFilterChainConfig}'s {@code permitAll()} list, mirroring the
 * existing {@code GET /api/v1/public/courses/**} precedent (see {@code
 * CoursePublicController}) - branding (logo, colors, institute name) must
 * render for anonymous visitors to the tenant's public storefront/login
 * page, and for authenticated students/teachers who hold no {@code
 * BRANDING_SETTINGS} grant at all (that grant is Tenant Admin/Read-only
 * Auditor only). This is display-only public information, not a privileged
 * settings read, so it deliberately bypasses {@code
 * TenantConfigController}'s domain-level permission gate rather than
 * reusing that endpoint.
 *
 * <p>Tenant identity still comes exclusively from {@code
 * TenantResolutionFilter}'s subdomain resolution - this controller is NOT
 * excluded from that filter, so {@link TenantContext} is already populated
 * for this request before this method runs, from the {@code Host} header,
 * never from a client-supplied tenant id/query param. This reuses {@code
 * tenant-management}'s existing sole tenant-resolution mechanism verbatim
 * (per {@code .claude/rules/tenancy.md}) rather than building a second one.
 */
@RestController
@RequestMapping("/api/v1/public/tenant-config/branding")
public class PublicBrandingController {

	/** Platform-level fallback used when a tenant has no BRANDING config rows at all yet. */
	private static final String DEFAULT_PRIMARY_COLOR = "#1D4ED8";

	private static final String DEFAULT_SECONDARY_COLOR = "#0F172A";

	private static final String DEFAULT_INSTITUTE_NAME = "";

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public PublicBrandingController(TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PublicBrandingResponse>> getBranding() {
		UUID tenantId = tenantContext.getTenantId();
		Map<String, Object> branding = tenantConfigApi.resolveDomain(tenantId, ConfigDomain.BRANDING);
		Map<String, Object> general = tenantConfigApi.resolveDomain(tenantId, ConfigDomain.GENERAL);
		PublicBrandingResponse response = new PublicBrandingResponse(
				stringOrDefault(branding.get("primary_color"), DEFAULT_PRIMARY_COLOR),
				stringOrDefault(branding.get("secondary_color"), DEFAULT_SECONDARY_COLOR),
				stringOrDefault(branding.get("logo_url"), null), stringOrDefault(branding.get("favicon_url"), null),
				stringOrDefault(general.get("institute_name"), DEFAULT_INSTITUTE_NAME));
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	private static String stringOrDefault(Object value, String fallback) {
		return (value instanceof String s) ? s : fallback;
	}

}
