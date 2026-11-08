package com.lms.identityaccessservice.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.identityaccessservice.service.permission.PermissionChange;
import com.lms.identityaccessservice.service.permission.PermissionMatrixView;
import com.lms.identityaccessservice.service.permission.RolePermissionMatrixService;
import com.lms.identityaccessservice.web.dto.PermissionMatrixUpdateRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * STAFF-MGMT-02 §2.5: the tenant's configurable role-permission matrix (Operator, Manager,
 * Teacher). Read = {@link Capability#STAFF_VIEW}; save = {@link Capability#STAFF_PERMISSIONS_EDIT}
 * (both LOCKED, so Tenant Admin / Auditor only). Thin; {@link RolePermissionMatrixService}
 * re-checks the capability and owns validation, concurrency and audit.
 */
@RestController
@RequestMapping("/api/v1/staff/permission-matrix")
public class PermissionMatrixController {

	private final RolePermissionMatrixService matrixService;

	public PermissionMatrixController(RolePermissionMatrixService matrixService) {
		this.matrixService = matrixService;
	}

	@GetMapping
	@RequiresCapability(Capability.STAFF_VIEW)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_VIEW')")
	public ApiResponse<PermissionMatrixView> get() {
		return ApiResponse.success(matrixService.read());
	}

	@PutMapping
	@RequiresCapability(Capability.STAFF_PERMISSIONS_EDIT)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_PERMISSIONS_EDIT')")
	public ApiResponse<PermissionMatrixView> update(@Valid @RequestBody PermissionMatrixUpdateRequest request) {
		return ApiResponse.success(matrixService.update(request.expectedVersion(),
				request.changes()
					.stream()
					.map(c -> new PermissionChange(c.roleCode(), c.capabilityKey(), c.granted()))
					.toList()));
	}

}
