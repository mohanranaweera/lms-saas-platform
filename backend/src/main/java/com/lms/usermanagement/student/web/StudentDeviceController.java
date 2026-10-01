package com.lms.usermanagement.student.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.DeviceSlotSummary;
import com.lms.identityaccessservice.api.DeviceView;
import com.lms.usermanagement.student.service.StudentDeviceService;
import com.lms.usermanagement.student.web.dto.StudentDeviceRequests.DeviceLimitRequest;
import com.lms.usermanagement.student.web.dto.StudentDeviceRequests.DeviceResetAllResponse;
import com.lms.usermanagement.student.web.dto.StudentDeviceRequests.DeviceResetRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wave 10 - Student detail Devices tab ({@code {studentId}} is the {@code
 * student_profile} id, like every other {@code /students/{id}} endpoint).
 * {@code DEVICES} V to read (Tenant Admin, Student Support, Auditor) and C/E
 * to reset or change the limit (Tenant Admin). The service re-checks both.
 */
@RestController
@RequestMapping("/api/v1/students/{studentId}")
public class StudentDeviceController {

	private final StudentDeviceService studentDeviceService;

	public StudentDeviceController(StudentDeviceService studentDeviceService) {
		this.studentDeviceService = studentDeviceService;
	}

	@GetMapping("/devices")
	@PreAuthorize("@permissionCheckService.hasPermission('DEVICES', 'VIEW')")
	public ResponseEntity<ApiResponse<DeviceSlotSummary>> getDevices(@PathVariable UUID studentId) {
		return ResponseEntity.ok(ApiResponse.success(studentDeviceService.getDevices(studentId)));
	}

	@PostMapping("/devices/{deviceId}/reset")
	@PreAuthorize("@permissionCheckService.hasPermission('DEVICES', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<DeviceView>> resetDevice(@PathVariable UUID studentId,
			@PathVariable UUID deviceId, @Valid @RequestBody DeviceResetRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(studentDeviceService.resetDevice(studentId, deviceId, request.reason())));
	}

	@PostMapping("/devices/reset")
	@PreAuthorize("@permissionCheckService.hasPermission('DEVICES', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<DeviceResetAllResponse>> resetAllDevices(@PathVariable UUID studentId,
			@Valid @RequestBody DeviceResetRequest request) {
		int count = studentDeviceService.resetAllDevices(studentId, request.reason());
		return ResponseEntity.ok(ApiResponse.success(new DeviceResetAllResponse(count)));
	}

	@PutMapping("/device-limit")
	@PreAuthorize("@permissionCheckService.hasPermission('DEVICES', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<DeviceSlotSummary>> setDeviceLimit(@PathVariable UUID studentId,
			@Valid @RequestBody DeviceLimitRequest request) {
		return ResponseEntity.ok(ApiResponse
			.success(studentDeviceService.setDeviceLimit(studentId, request.maxDevices(), request.reason())));
	}

}
