package com.lms.identityaccessservice.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Wave 10 tenant device policy update. {@code maxDevices} null = use the plan
 * default. Bounds mirror the V64 CHECK constraints.
 */
public record DevicePolicyRequest(@Min(1) @Max(20) Integer maxDevices,
		@NotNull @Min(0) @Max(720) Integer resetCooldownHours, @NotNull Boolean studentSelfServiceEnabled) {

}
