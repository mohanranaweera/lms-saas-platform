package com.lms.identityaccessservice.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.repository.CapabilityGrantStore;
import com.lms.identityaccessservice.web.dto.MyPermissionsResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAFF-MGMT-02 §2.5: {@code GET /api/v1/me/permissions} - the caller's live role and effective
 * capabilities, for UI gating only (the backend re-checks every operation). A principal without
 * a tenant (Platform Admin) gets an empty list.
 */
@RestController
@RequestMapping("/api/v1/me/permissions")
@PreAuthorize("isAuthenticated()")
public class MyPermissionsController {

	private final PermissionCheckService permissionCheckService;

	private final CapabilityGrantStore grantStore;

	public MyPermissionsController(PermissionCheckService permissionCheckService, CapabilityGrantStore grantStore) {
		this.permissionCheckService = permissionCheckService;
		this.grantStore = grantStore;
	}

	@GetMapping
	public ApiResponse<MyPermissionsResponse> get() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (principal.tenantId() == null || !TenantContextHolder.isSet()) {
			return ApiResponse.success(new MyPermissionsResponse(principal.role(), List.of(), 0L));
		}
		List<String> keys = permissionCheckService.effectiveCapabilities().stream().map(Capability::key).toList();
		return ApiResponse.success(new MyPermissionsResponse(principal.role(), keys, grantStore.currentVersion()));
	}

}
