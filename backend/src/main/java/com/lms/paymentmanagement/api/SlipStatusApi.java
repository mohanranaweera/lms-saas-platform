package com.lms.paymentmanagement.api;

import java.util.List;
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

	/**
	 * Wave 6 (§3.2/§4) batched read - one {@link OrderSlipDetail} per id in
	 * {@code orderIds} that has at least one slip in the current tenant
	 * context; an order with no slip at all is simply absent from the
	 * result (mirrors {@link PaymentStatusApi#findOrderPaymentDetails}'s
	 * exact "absent, never an error" contract). Backs {@code
	 * ledger-settlement-management}'s {@code PaymentOperationalState}
	 * projection.
	 */
	List<OrderSlipDetail> findOrderSlipDetails(List<UUID> orderIds);

}
