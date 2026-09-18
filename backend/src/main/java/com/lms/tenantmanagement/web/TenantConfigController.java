package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.service.ConfigDomainSummary;
import com.lms.tenantmanagement.service.ConfigPropertyValue;
import com.lms.tenantmanagement.service.TenantConfigService;
import com.lms.tenantmanagement.web.dto.ConfigDomainSummaryResponse;
import com.lms.tenantmanagement.web.dto.ConfigPropertyResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant Admin (and Read-only Auditor, view-only) typed configuration
 * endpoints (Wave 1). Gated by {@code PermissionCheckService}'s {@code
 * BRANDING_SETTINGS} domain area - the closest existing grant to
 * "tenant-wide settings" in {@code docs/requirements/user-roles-and-permissions.md}
 * §2's matrix (Tenant Admin: view/create-edit; Read-only Auditor: view
 * only; every other staff sub-role/Teacher/Teacher Assistant/Student: no
 * access). {@code {domain}} is bound directly to {@link ConfigDomain} -
 * Spring rejects an unrecognized domain name with {@code 400} (via {@code
 * GlobalExceptionHandler#handleTypeMismatch}), never a raw {@code 500}.
 * Stays thin - all logic in {@link TenantConfigService}.
 */
@RestController
@RequestMapping("/api/v1/tenant-config")
public class TenantConfigController {

	private final TenantConfigService tenantConfigService;

	public TenantConfigController(TenantConfigService tenantConfigService) {
		this.tenantConfigService = tenantConfigService;
	}

	@GetMapping("/domains")
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'VIEW')")
	public ResponseEntity<ApiResponse<List<ConfigDomainSummaryResponse>>> listDomains() {
		List<ConfigDomainSummaryResponse> domains = tenantConfigService.listDomainSummaries()
			.stream()
			.map(TenantConfigController::toSummaryResponse)
			.toList();
		return ResponseEntity.ok(ApiResponse.success(domains));
	}

	@GetMapping("/{domain}")
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'VIEW')")
	public ResponseEntity<ApiResponse<List<ConfigPropertyResponse>>> getDomain(@PathVariable ConfigDomain domain) {
		return ResponseEntity.ok(ApiResponse.success(toPropertyResponses(tenantConfigService.getDomain(domain))));
	}

	@PutMapping("/{domain}")
	@PreAuthorize("@permissionCheckService.hasPermission('BRANDING_SETTINGS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<List<ConfigPropertyResponse>>> updateDomain(@PathVariable ConfigDomain domain,
			@RequestBody Map<String, Object> changes) {
		List<ConfigPropertyValue> updated = tenantConfigService.updateDomain(domain, changes);
		return ResponseEntity.ok(ApiResponse.success(toPropertyResponses(updated)));
	}

	private static ConfigDomainSummaryResponse toSummaryResponse(ConfigDomainSummary summary) {
		return new ConfigDomainSummaryResponse(summary.domain().name(), summary.hasProperties());
	}

	private static List<ConfigPropertyResponse> toPropertyResponses(List<ConfigPropertyValue> values) {
		return values.stream()
			.map(value -> new ConfigPropertyResponse(value.key(), value.value(), value.type(), value.defaultValue(),
					value.sensitive()))
			.toList();
	}

}
