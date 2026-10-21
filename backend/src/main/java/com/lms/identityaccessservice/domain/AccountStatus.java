package com.lms.identityaccessservice.domain;

/**
 * Shared by {@code tenant_user.status} (V3) and {@code
 * platform_admin_user.status} (V4) - both use the identical
 * {@code CHECK (status IN ('active','suspended'))} vocabulary.
 */
public enum AccountStatus {

	ACTIVE, SUSPENDED,

	/**
	 * AUTH-PWD-01 / ADR-020: created by an invitation (e.g. a tenant's first
	 * Tenant Admin) and not yet set up. Holds an unusable password; becomes
	 * {@link #ACTIVE} only by completing its one-time account-setup link.
	 * Never able to log in or refresh.
	 */
	INVITED

}
