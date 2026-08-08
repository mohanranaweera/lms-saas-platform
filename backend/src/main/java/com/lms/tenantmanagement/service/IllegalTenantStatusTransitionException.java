package com.lms.tenantmanagement.service;

import com.lms.tenantmanagement.api.TenantStatus;

/**
 * Thrown by {@link TenantStatusService} when a requested transition is not
 * in the legal transition graph (e.g. anything out of {@code REJECTED}/
 * {@code CANCELLED}, or {@code SUSPENDED -> ACTIVE} directly - reactivation
 * has no defined path per the plan's open decisions).
 *
 * <p>This class is not wired to any controller/exception-handler mapping in
 * this slice - {@link TenantStatusService} itself is pure, unwired domain
 * logic (see its own doc comment).
 */
public class IllegalTenantStatusTransitionException extends IllegalStateException {

	public IllegalTenantStatusTransitionException(TenantStatus current, TenantTransition transition) {
		super("Transition " + transition + " is not legal from status " + current);
	}

}
