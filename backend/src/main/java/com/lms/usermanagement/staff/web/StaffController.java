package com.lms.usermanagement.staff.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.LoginMobileRequest;
import com.lms.identityaccessservice.api.LoginMobileResponse;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.usermanagement.staff.service.AssignableRole;
import com.lms.usermanagement.staff.service.StaffAccount;
import com.lms.usermanagement.staff.service.StaffActivityService;
import com.lms.usermanagement.staff.service.StaffService;
import com.lms.usermanagement.staff.web.dto.StaffCreateRequest;
import com.lms.usermanagement.staff.web.dto.StaffResponse;
import com.lms.usermanagement.staff.web.dto.StaffRoleChangeRequest;
import com.lms.usermanagement.staff.web.dto.StaffUpdateRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff Management endpoints (MVP-005, reworked by STAFF-MGMT-02 §2.5). Every method carries
 * {@code @RequiresCapability} (all STAFF_* capabilities are LOCKED, so Operator/Manager are
 * denied by the interceptor) plus the equivalent {@code @PreAuthorize}; {@link StaffService}
 * re-checks again. Thin: no business logic here. Tenant always from the trusted context.
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffController {

	private final StaffService staffService;

	private final StaffActivityService staffActivityService;

	public StaffController(StaffService staffService, StaffActivityService staffActivityService) {
		this.staffService = staffService;
		this.staffActivityService = staffActivityService;
	}

	@GetMapping
	@RequiresCapability(Capability.STAFF_VIEW)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_VIEW')")
	public ApiResponse<PageResponse<StaffResponse>> list(@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ApiResponse
			.success(PageResponse.from(staffService.searchStaff(q, page, size).map(StaffController::toResponse)));
	}

	@GetMapping("/assignable-roles")
	@RequiresCapability(Capability.STAFF_MANAGE)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_MANAGE')")
	public ApiResponse<List<AssignableRole>> assignableRoles() {
		return ApiResponse.success(staffService.assignableRoles());
	}

	@GetMapping("/activities")
	@RequiresCapability(Capability.STAFF_ACTIVITY_VIEW)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_ACTIVITY_VIEW')")
	public ApiResponse<PageResponse<StaffActivityService.StaffActivity>> activities(
			@RequestParam(required = false) String q,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return ApiResponse.success(PageResponse.from(staffActivityService.list(q, from, to, page, size)));
	}

	@GetMapping("/{id}")
	@RequiresCapability(Capability.STAFF_VIEW)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_VIEW')")
	public ApiResponse<StaffResponse> get(@PathVariable UUID id) {
		return ApiResponse.success(toResponse(staffService.getStaff(id)));
	}

	@PostMapping
	@RequiresCapability(Capability.STAFF_MANAGE)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_MANAGE')")
	public ResponseEntity<ApiResponse<StaffResponse>> create(@Valid @RequestBody StaffCreateRequest request) {
		StaffAccount account = staffService.createStaff(request.firstName(), request.lastName(), request.email(),
				request.mobileNumber(), request.roleCode());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(account)));
	}

	@PatchMapping("/{id}")
	@RequiresCapability(Capability.STAFF_MANAGE)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_MANAGE')")
	public ApiResponse<StaffResponse> update(@PathVariable UUID id, @Valid @RequestBody StaffUpdateRequest request) {
		return ApiResponse.success(toResponse(
				staffService.updateProfile(id, request.firstName(), request.lastName(), request.contactNumber())));
	}

	@PutMapping("/{id}/role")
	@RequiresCapability(Capability.STAFF_ASSIGN_ROLES)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_ASSIGN_ROLES')")
	public ApiResponse<StaffResponse> changeRole(@PathVariable UUID id,
			@Valid @RequestBody StaffRoleChangeRequest request) {
		return ApiResponse.success(toResponse(staffService.changeRole(id, request.roleCode())));
	}

	@PostMapping("/{id}/deactivate")
	@RequiresCapability(Capability.STAFF_MANAGE)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_MANAGE')")
	public ApiResponse<StaffResponse> deactivate(@PathVariable UUID id) {
		return ApiResponse.success(toResponse(staffService.deactivate(id)));
	}

	@PostMapping("/{id}/reactivate")
	@RequiresCapability(Capability.STAFF_MANAGE)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_MANAGE')")
	public ApiResponse<StaffResponse> reactivate(@PathVariable UUID id) {
		return ApiResponse.success(toResponse(staffService.reactivate(id)));
	}

	@PostMapping("/{id}/resend-invite")
	@RequiresCapability(Capability.STAFF_MANAGE)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_MANAGE')")
	public ApiResponse<StaffResponse> resendInvite(@PathVariable UUID id) {
		return ApiResponse.success(toResponse(staffService.resendInvite(id)));
	}

	/** ADR-031: the staff member's sign-in mobile. */
	@GetMapping("/{id}/login-mobile")
	@RequiresCapability(Capability.STAFF_VIEW)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_VIEW')")
	public ApiResponse<LoginMobileResponse> getLoginMobile(@PathVariable UUID id) {
		return ApiResponse.success(new LoginMobileResponse(staffService.getLoginMobile(id)));
	}

	/** ADR-031: set or remove (blank) the staff member's sign-in mobile. */
	@PutMapping("/{id}/login-mobile")
	@RequiresCapability(Capability.STAFF_MANAGE)
	@PreAuthorize("@permissionCheckService.hasCapability('STAFF_MANAGE')")
	public ApiResponse<LoginMobileResponse> setLoginMobile(@PathVariable UUID id,
			@Valid @RequestBody LoginMobileRequest request) {
		return ApiResponse.success(new LoginMobileResponse(staffService.setLoginMobile(id, request.loginMobile())));
	}

	private static StaffResponse toResponse(StaffAccount account) {
		StaffAccount.Actions a = account.actions();
		return new StaffResponse(account.id(), account.firstName(), account.lastName(), account.name(),
				account.email(), account.contactNumber(), account.roleCode(), account.roleName(), account.status(),
				account.lastLoginAt(), new StaffResponse.Actions(a.canEdit(), a.canChangeRole(), a.canDeactivate(),
						a.canReactivate(), a.canResendInvite()));
	}

}
