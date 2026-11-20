package com.lms.identityaccessservice.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.DeviceSlotSummary;
import com.lms.identityaccessservice.service.DeviceManagementService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 10 - the signed-in tenant user own devices ("My Devices"). Always
 * scoped to the principal resolved by the JWT filter; there is no user id in
 * the path. Self-revocation rules live in {@link DeviceManagementService}.
 */
@RestController
@RequestMapping("/api/v1/devices/my")
public class DeviceController {

	private final DeviceManagementService deviceManagementService;

	public DeviceController(DeviceManagementService deviceManagementService) {
		this.deviceManagementService = deviceManagementService;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<DeviceSlotSummary>> getMyDevices() {
		return ResponseEntity.ok(ApiResponse.success(deviceManagementService.getMyDevices()));
	}

	@PostMapping("/{deviceId}/revoke")
	public ResponseEntity<ApiResponse<DeviceSlotSummary>> revokeMyDevice(@PathVariable UUID deviceId) {
		return ResponseEntity.ok(ApiResponse.success(deviceManagementService.revokeMyDevice(deviceId)));
	}

}
