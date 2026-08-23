package com.lms.paymentmanagement.api;

import java.util.List;
import java.util.UUID;

/**
 * The narrow, read-only contract other domains are permitted to depend on
 * for payment status - mirroring {@code coursemanagement.api.CourseLookupApi}'s
 * shape for a different concern. Every method here resolves tenant identity
 * from the already-resolved {@link com.lms.common.tenant.TenantContext} -
 * there is no overload that accepts a caller-supplied tenant id.
 *
 * <p>{@link #isConfirmedForCurrentTenant(UUID)} is the specific method
 * {@code enrollment-management}'s {@code EnrollmentActivationService} calls
 * to independently re-verify a payment is genuinely {@code CONFIRMED} before
 * activating enrollment - never trusting the calling service's claim alone
 * (defense in depth, mirroring {@code StaffService}'s established
 * independent-permission-recheck pattern). By the time this is called from
 * the webhook-confirmation flow, {@link com.lms.common.tenant.TenantContextHolder}
 * has already been explicitly set to the payment's own trusted tenant id
 * (see {@code PaymentConfirmationService}'s javadoc) - this method never
 * itself resolves tenant identity from anything client-supplied.
 */
public interface PaymentStatusApi {

	/**
	 * @return {@code true} only if {@code paymentId} exists in the current
	 * tenant context and its status is {@code CONFIRMED}; {@code false} for
	 * a nonexistent, non-terminal, rejected, or cross-tenant id.
	 */
	boolean isConfirmedForCurrentTenant(UUID paymentId);

	/**
	 * @return the ids of every order placed by {@code studentId} within the
	 * current tenant context - used by {@code ledger-settlement-management}
	 * to scope a student's own Payment History read to their own orders
	 * only, without that module needing a JPA association into {@code
	 * payment-management}'s {@code student_order} table.
	 */
	List<UUID> findOrderIdsForStudent(UUID studentId);

}
