package com.lms.enrollmentmanagement.api;

import java.util.UUID;

/**
 * The narrow, read-only contract other domains (namely {@code
 * payment-management}'s {@code OrderService}, and any future {@code
 * content-management}/{@code video-access-management} caller) are permitted
 * to depend on for enrollment access-currency reads (MVP-012/ADR-013).
 * Mirrors {@code paymentmanagement.api.PaymentStatusApi}'s tenant-context
 * discipline exactly - every method resolves tenant identity exclusively
 * from {@link com.lms.common.tenant.TenantContext}; there is no overload
 * that accepts a caller-supplied tenant id.
 *
 * <p>Deliberately minimal (two narrow methods) - per plan §9's "deliberately
 * minimal" framing, this interface does not grow a general-purpose
 * enrollment query surface; a future consumer needing something else should
 * add a narrowly-scoped method here, not reach into this module's {@code
 * domain}/{@code repository} packages.
 */
public interface EnrollmentAccessApi {

	/**
	 * Computed LIVE on every call - {@code NOT superseded AND
	 * (accessExpiresAt IS NULL OR accessExpiresAt > now())} - never itself
	 * the source of an update to {@code enrollment}. The first time a live
	 * check observes {@link EnrollmentAccessStateType#EXPIRED}, this performs
	 * one idempotent, guarded write to {@code enrollment_expiry_event} (plan
	 * §4.1 step 3) - a side effect of an otherwise read-only call, by design.
	 * @return a state describing the given (student, course) pair - see
	 * {@link EnrollmentAccessState}'s own javadoc for why this is never
	 * {@code Optional}.
	 */
	EnrollmentAccessState resolveAccessState(UUID studentId, UUID courseId);

	/**
	 * @return {@code true} only if a current ({@code supersededAt IS NULL})
	 * enrollment exists for (studentId, courseId) AND it has an {@code
	 * APPROVED}, unfulfilled ({@code newOrderId IS NULL}) reactivation
	 * request. This is the exact precondition {@code
	 * OrderService.createOrder} (ADR-013 §9) must check BEFORE attempting to
	 * create a new order for an expired enrollment - kept as its own method,
	 * separate from {@link ReactivationLinkingApi#linkApprovedRequestToNewOrder},
	 * so {@code OrderService} can decide "409 vs proceed" before any write is
	 * attempted, rather than discovering the answer via a failed link call.
	 */
	boolean hasApprovedUnfulfilledReactivationRequest(UUID studentId, UUID courseId);

}
