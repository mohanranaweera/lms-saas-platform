package com.lms.identityaccessservice.api;

/** Whether a {@link Capability} can be configured for a tenant role (STAFF-MGMT-02 §2.2). */
public enum CapabilityAvailability {

	/** Backed by real endpoints carrying {@link RequiresCapability}; grantable to its configurable roles. */
	ENFORCED,

	/** System-managed (Tenant Admin only or an alias); never grantable. */
	LOCKED,

	/** No backing operation exists; shown for Klass parity, never grantable. */
	UNAVAILABLE

}
