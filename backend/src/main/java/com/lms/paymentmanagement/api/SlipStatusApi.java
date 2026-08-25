package com.lms.paymentmanagement.api;

import java.util.UUID;

/**
 * The narrow, read-only contract other domains are permitted to depend on
 * for manual-payment-slip status - mirrors {@link PaymentStatusApi}'s exact
 * shape and tenant-context discipline for the sibling manual-slip
 * confirmation path (MVP-011).
 *
 * <p>{@link #isApprovedForCurrentTenant(UUID)} is the specific method {@code
 * enrollment-management}'s {@code EnrollmentActivationService} calls to
 * independently re-verify a slip is genuinely {@code APPROVED} before
 * activating enrollment - never trusting the calling service's claim alone
 * (defense in depth, mirroring {@code PaymentStatusApi.isConfirmedForCurrentTenant}'s
 * established precedent). This method always resolves tenant identity from
 * the already-resolved {@link com.lms.common.tenant.TenantContext} - there is
 * no overload that accepts a caller-supplied tenant id.
 */
public interface SlipStatusApi {

	/**
	 * @return {@code true} only if {@code slipId} exists in the current
	 * tenant context and its status is {@code APPROVED}; {@code false} for a
	 * nonexistent, non-terminal, rejected, or cross-tenant id.
	 */
	boolean isApprovedForCurrentTenant(UUID slipId);

}
