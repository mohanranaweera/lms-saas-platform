package com.lms.usermanagement.student.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Wave 10 request bodies for the Student detail Devices tab. */
public final class StudentDeviceRequests {

	private StudentDeviceRequests() {
	}

	/** Reset one device or all devices. The reason is mandatory and audit-logged. */
	public record DeviceResetRequest(@NotBlank @Size(max = 500) String reason) {
	}

	/** Student device-limit exception. {@code maxDevices} null clears it. */
	public record DeviceLimitRequest(@Min(1) @Max(20) Integer maxDevices, @NotBlank @Size(max = 500) String reason) {
	}

	public record DeviceResetAllResponse(int revokedCount) {
	}

}
