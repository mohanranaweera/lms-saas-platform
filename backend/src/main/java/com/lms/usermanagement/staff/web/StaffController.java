package com.lms.usermanagement.staff.web;

import com.lms.common.api.ApiResponse;
import com.lms.usermanagement.staff.service.StaffAccount;
import com.lms.usermanagement.staff.service.StaffService;
import com.lms.usermanagement.staff.web.dto.StaffCreateRequest;
import com.lms.usermanagement.staff.web.dto.StaffResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff Management (MVP-005, {@code STAFF-1}) endpoints. Every method is
 * gated by {@code PermissionCheckService}'s {@code STAFF_AND_ROLES} domain
 * area, matching {@code docs/requirements/user-roles-and-permissions.md}
 * §2's matrix (Tenant Admin: view/create/edit/delete; Read-only Auditor:
 * view only; every other role: no access at all, not even view) - stays
 * thin, delegates entirely to {@link StaffService}. No role-edit,
 * delete/deactivate, activity-log, or password-reset endpoint is built in
 * this pass (see MVP-005 brief - those depend on still-open decisions).
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffController {

	private final StaffService staffService;

	public StaffController(StaffService staffService) {
		this.staffService = staffService;
	}

	@PostMapping
	@PreAuthorize("@permissionCheckService.hasPermission('STAFF_AND_ROLES', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<StaffResponse>> createStaff(@Valid @RequestBody StaffCreateRequest request) {
		StaffAccount account = staffService.createStaff(request.name(), request.email(), request.password(),
				request.roleCode());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(account)));
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('STAFF_AND_ROLES', 'VIEW')")
	public ResponseEntity<ApiResponse<List<StaffResponse>>> listStaff() {
		List<StaffResponse> staff = staffService.listStaff().stream().map(StaffController::toResponse).toList();
		return ResponseEntity.ok(ApiResponse.success(staff));
	}

	@GetMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('STAFF_AND_ROLES', 'VIEW')")
	public ResponseEntity<ApiResponse<StaffResponse>> getStaff(@PathVariable UUID id) {
		StaffAccount account = staffService.getStaff(id);
		return ResponseEntity.ok(ApiResponse.success(toResponse(account)));
	}

	private static StaffResponse toResponse(StaffAccount account) {
		return new StaffResponse(account.id(), account.name(), account.email(), account.roleCode(),
				account.status());
	}

}
