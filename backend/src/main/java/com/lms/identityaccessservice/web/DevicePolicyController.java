package com.lms.identityaccessservice.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.DevicePolicyView;
import com.lms.identityaccessservice.service.DevicePolicyService;
import com.lms.identityaccessservice.web.dto.DevicePolicyRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 10 - the tenant device policy (device limit, reset cooldown, student
 * self-service). {@code DEVICES} V / C/E, checked here and again in the
 * service; the tenant comes from the resolved request context only.
 */
@RestController
@RequestMapping("/api/v1/device-policy")
public class DevicePolicyController {

	private final DevicePolicyService devicePolicyService;

	public DevicePolicyController(DevicePolicyService devicePolicyService) {
		this.devicePolicyService = devicePolicyService;
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('DEVICES', 'VIEW')")
	public ResponseEntity<ApiResponse<DevicePolicyView>> getPolicy() {
		return ResponseEntity.ok(ApiResponse.success(devicePolicyService.getPolicy()));
	}

	@PutMapping
	@PreAuthorize("@permissionCheckService.hasPermission('DEVICES', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<DevicePolicyView>> updatePolicy(@Valid @RequestBody DevicePolicyRequest request) {
		return ResponseEntity.ok(ApiResponse.success(devicePolicyService.updatePolicy(request.maxDevices(),
				request.resetCooldownHours(), request.studentSelfServiceEnabled())));
	}

}
