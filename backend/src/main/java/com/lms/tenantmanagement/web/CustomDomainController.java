package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.tenantmanagement.service.CustomDomainService;
import com.lms.tenantmanagement.web.dto.CustomDomainResponse;
import com.lms.tenantmanagement.web.dto.CustomDomainsResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TEN-DOM-01 / ADR-021: the Tenant Admin's custom domains. Tenant comes from
 * the resolved TenantContext only; {@code CUSTOM_DOMAINS} VIEW to read,
 * CREATE_EDIT to change (Tenant Admin; Read-only Auditor views). The literal
 * {@code custom-domains} segment takes precedence over {@link
 * TenantConfigController}'s {@code /{domain}} pattern.
 */
@RestController
@RequestMapping("/api/v1/tenant-config/custom-domains")
public class CustomDomainController {

	private final CustomDomainService customDomainService;

	public CustomDomainController(CustomDomainService customDomainService) {
		this.customDomainService = customDomainService;
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('CUSTOM_DOMAINS', 'VIEW')")
	public ResponseEntity<ApiResponse<CustomDomainsResponse>> list() {
		return ResponseEntity.ok(ApiResponse.success(CustomDomainsResponse.from(customDomainService.overview())));
	}

	@PostMapping
	@PreAuthorize("@permissionCheckService.hasPermission('CUSTOM_DOMAINS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<CustomDomainResponse>> add(@Valid @RequestBody AddCustomDomainRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(CustomDomainResponse.from(customDomainService.add(request.hostname()))));
	}

	@PostMapping("/{id}/verify")
	@PreAuthorize("@permissionCheckService.hasPermission('CUSTOM_DOMAINS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<CustomDomainResponse>> verify(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(CustomDomainResponse.from(customDomainService.verify(id))));
	}

	@PostMapping("/{id}/primary")
	@PreAuthorize("@permissionCheckService.hasPermission('CUSTOM_DOMAINS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<CustomDomainResponse>> makePrimary(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(CustomDomainResponse.from(customDomainService.makePrimary(id))));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('CUSTOM_DOMAINS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<Void>> remove(@PathVariable UUID id) {
		customDomainService.remove(id);
		// 200 + empty envelope, the project's DELETE convention (e.g. QuestionBankController).
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	public record AddCustomDomainRequest(@NotBlank @Size(max = 253) String hostname) {

	}

}
