package com.lms.identityaccessservice.api;

import java.time.Instant;
import java.util.List;

/**
 * A user's devices plus the resolved login device limit (Wave 10).
 * {@code occupiedSlots} counts active devices plus revoked devices still in
 * cooldown; {@code nextSlotFreeAt} is the earliest cooldown end when that
 * blocks a new device, else null. {@code limit} is null for staff accounts
 * (not device-limited). {@code studentOverride}/{@code studentOverrideReason}
 * describe the student-level exception, if any.
 */
public record DeviceSlotSummary(List<DeviceView> devices, Integer limit, DeviceLimitSource limitSource,
		int occupiedSlots, Instant nextSlotFreeAt, Integer studentOverride, String studentOverrideReason,
		boolean selfServiceEnabled, int resetCooldownHours) {

}
