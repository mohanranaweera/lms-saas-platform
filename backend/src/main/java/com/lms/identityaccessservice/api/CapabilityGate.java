package com.lms.identityaccessservice.api;

/**
 * What {@code CapabilityEnforcementInterceptor} does for one {@link RequiresCapability} handler
 * and the current actor (STAFF-MGMT-02 §2.3, STAFF-MGMT-03 §6.1). Decided by
 * {@link PermissionCheckService#capabilityGate(Capability)}.
 */
public enum CapabilityGate {

	/** No effect: the endpoint's existing static / service checks decide (unchanged behaviour). */
	PASS,

	/** Granted by the tenant: the capability's implied pairs are elevated for this request only. */
	ELEVATE,

	/** Not granted / explicitly revoked: 403 before the handler runs. */
	DENY

}
