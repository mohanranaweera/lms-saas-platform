package com.lms.identityaccessservice.api;

import java.time.Instant;

/**
 * The tenant device policy as configured ({@code maxDevices} null = plan
 * default) plus the effective limit and plan default for display.
 */
public record DevicePolicyView(Integer maxDevices, int effectiveMaxDevices, int planDefaultLimit,
		int resetCooldownHours, boolean studentSelfServiceEnabled, Instant updatedAt) {

}
