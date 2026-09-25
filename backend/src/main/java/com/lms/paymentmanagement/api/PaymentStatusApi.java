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

	/**
	 * Wave 6 (§3.2/§4) batched read - one {@link OrderPaymentDetail} per id in
	 * {@code orderIds} that resolves to a real order in the current tenant
	 * context (a nonexistent/cross-tenant id is simply absent from the
	 * result, mirroring {@link
	 * com.lms.coursemanagement.api.CourseLookupApi#getCourseSummaries}'s
	 * established "absent, never an error" contract). Backs {@code
	 * ledger-settlement-management}'s {@code PaymentOperationalState}
	 * projection and the extended ledger views' {@code courseId}/{@code
	 * billingPeriodId}/{@code method}/{@code reference} fields.
	 */
	List<OrderPaymentDetail> findOrderPaymentDetails(List<UUID> orderIds);

	/**
	 * Wave 6 (§4) - every order id in the current tenant context, used by
	 * {@code ledger-settlement-management}'s {@code GET /api/v1/ledger/
	 * outstanding} to resolve which orders have a non-{@code PAID}/{@code
	 * REFUNDED} {@code PaymentOperationalState}. Acceptable at current data
	 * volumes per plan §10 judgment call 3 - a materialized read model is
	 * the correct future direction if tenant/order volume grows enough to
	 * matter, not a premature optimization here.
	 */
	List<UUID> findAllOrderIdsForCurrentTenant();

	/**
	 * Wave 6 (§4) - every order id for {@code courseId} within the current
	 * tenant context, used by {@code GET /api/v1/ledger/courses/{courseId}/
	 * summary}. A {@code courseId} that does not resolve to a course with
	 * any orders in the caller's own tenant (nonexistent, cross-tenant, or
	 * simply never ordered) returns an empty list - never distinguishable
	 * from each other, mirroring {@link
	 * com.lms.coursemanagement.api.CourseLookupApi}'s established
	 * "not-found vs. cross-tenant non-distinction" contract (anti
	 * -enumeration).
	 */
	List<UUID> findOrderIdsForCourse(UUID courseId);

}
