package com.lms.identityaccessservice.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Device dimension of the centralized access policy (Wave 10), consumed by
 * {@code enrollment-management}'s {@code AccessPolicyService}. Tenant from
 * {@code TenantContext}; no caller-supplied tenant id.
 */
public interface DeviceAccessApi {

	/**
	 * Whether the device behind {@code sessionId} may open content of a course
	 * whose own device limit is {@code courseMaxDevices} (null = no course
	 * override). Precedence student, course, tenant, plan: a student exception
	 * wins over the course limit; without a course override the login-time
	 * limit already applies and this returns true. When a course limit applies,
	 * the authorized devices are the user's N earliest-registered active
	 * devices. A session with no registered device (issued before Wave 10) is
	 * not authorized for a course with its own limit.
	 */
	boolean isSessionDeviceAuthorized(UUID userId, UUID sessionId, Integer courseMaxDevices);

	/**
	 * VIDEO Phase 8: the ACTIVE device behind one of {@code userId}'s signed-in
	 * sessions, or empty when the session has no registered device (issued
	 * before Wave 10) or that device was revoked.
	 */
	Optional<UUID> findActiveSessionDeviceId(UUID userId, UUID sessionId);

	/**
	 * VIDEO Phase 8: same rule as {@link #isSessionDeviceAuthorized} for an
	 * explicit device (the AOC Player exchange has no browser session). False
	 * when the device is not {@code userId}'s or is no longer ACTIVE.
	 */
	boolean isDeviceAuthorized(UUID userId, UUID deviceId, Integer courseMaxDevices);

}
