package com.lms.identityaccessservice.api;

import java.util.UUID;

/**
 * Staff-facing device management for one tenant user (Wave 10), consumed by
 * {@code user-management}'s student detail endpoints (which resolve the
 * {@code student_profile} id to a user id first). Every method resolves the
 * tenant from {@code TenantContext} and enforces {@code DEVICES} permissions
 * itself - VIEW to read, CREATE_EDIT to change - so the check cannot be
 * skipped by a caller. A user id that does not resolve in the current tenant
 * is a 404.
 */
public interface DeviceManagementApi {

	DeviceSlotSummary getDevices(UUID userId);

	/**
	 * Revokes one device (admin reset): its sessions are signed out and its slot
	 * enters the tenant reset cooldown. Reason mandatory; audit-logged in the
	 * caller transaction.
	 */
	DeviceView resetDevice(UUID userId, UUID deviceId, String reason);

	/** Revokes every active device of the user. Returns how many were revoked. */
	int resetAllDevices(UUID userId, String reason);

	DeviceSlotSummary setDeviceLimitOverride(UUID userId, int maxDevices, String reason);

	DeviceSlotSummary clearDeviceLimitOverride(UUID userId, String reason);

}
