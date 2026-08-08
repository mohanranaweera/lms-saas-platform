package com.lms.tenantmanagement.service;

/**
 * The set of Platform-Admin-initiated transitions {@link TenantStatusService}
 * validates. Not every {@link com.lms.tenantmanagement.api.TenantStatus}
 * pair is reachable via a single named transition (see
 * {@code TenantStatusService} for the full legal-transition graph).
 */
public enum TenantTransition {

	/** {@code PENDING_APPROVAL -> TRIAL} */
	APPROVE,

	/** {@code PENDING_APPROVAL -> REJECTED} */
	REJECT,

	/** {@code TRIAL -> ACTIVE} (plan/payment confirmed by a later module) */
	ACTIVATE_TRIAL_TO_PAID,

	/** {@code TRIAL/ACTIVE -> SUSPENDED} */
	SUSPEND,

	/** {@code TRIAL/ACTIVE/SUSPENDED -> CANCELLED} */
	CANCEL

}
