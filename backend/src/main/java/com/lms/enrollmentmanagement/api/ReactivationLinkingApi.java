package com.lms.enrollmentmanagement.api;

import java.util.UUID;

/**
 * The narrow, write-only contract {@code payment-management}'s {@code
 * OrderService} depends on to link a newly-created order onto an already-
 * approved reactivation request (MVP-012/ADR-013 §9). Called inside {@code
 * OrderService.createOrder}'s own transaction - Spring's default {@code
 * REQUIRED} propagation makes this join the caller's transaction
 * automatically, mirroring {@code AuditLogApi}'s own same-transaction
 * contract. Resolves tenant identity exclusively from {@link
 * com.lms.common.tenant.TenantContext} - no overload accepts a
 * caller-supplied tenant id.
 */
public interface ReactivationLinkingApi {

	/**
	 * Looks up the current ({@code supersededAt IS NULL}) enrollment for
	 * (studentId, courseId), then the {@code APPROVED}, unfulfilled ({@code
	 * newOrderId IS NULL}) reactivation request for that enrollment, and
	 * links {@code newOrderId} onto it.
	 *
	 * <p>{@code OrderService} MUST have already confirmed, via {@link
	 * EnrollmentAccessApi#hasApprovedUnfulfilledReactivationRequest(UUID, UUID)},
	 * that this precondition holds BEFORE calling this method and BEFORE
	 * creating the order - this method's own {@link IllegalStateException} is
	 * a defense-in-depth re-verification, not the primary control-flow gate
	 * (plan §9: "OrderService maps this to 409, never proceeds to create the
	 * order" - i.e. the 409 decision must not be discovered by catching this
	 * exception after the order already exists).
	 * @throws IllegalStateException if no current enrollment, or no {@code
	 * APPROVED} unfulfilled reactivation request, resolves for (studentId,
	 * courseId) in the current tenant context.
	 */
	void linkApprovedRequestToNewOrder(UUID studentId, UUID courseId, UUID newOrderId);

}
