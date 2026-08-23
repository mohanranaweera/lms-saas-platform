package com.lms.enrollmentmanagement.api;

import java.util.UUID;

/**
 * The only contract other domains (namely {@code payment-management}, and -
 * for the future Module 11 slip path - eventually {@code
 * manual-payment-slip-management}) are permitted to depend on for
 * enrollment activation. Per plan §15, only two code paths may ever call
 * this, structurally: a verified webhook confirming a payment (this
 * module's only wired call site today), and - later - a slip reaching
 * {@code APPROVED} via authorized reviewer action. No endpoint - checkout,
 * redirect-return handler, order-status endpoint, or admin tooling - may
 * accept a client-reported "payment succeeded" payload as activation
 * evidence.
 */
public interface EnrollmentActivationApi {

	/**
	 * Idempotent via {@code uq_enrollment_tenant_student_course} (V19) - a
	 * repeated call for the same (tenant, student, course) is a no-op, never
	 * a second row. Before writing the {@code enrollment} row, independently
	 * re-verifies via {@code PaymentStatusApi} that {@code paymentId} is
	 * genuinely {@code CONFIRMED} for the current tenant - never trusts the
	 * caller's claim alone, even though the only current caller is this same
	 * module's own trusted {@code PaymentConfirmationService} (defense in
	 * depth per the plan's explicit requirement that this minimal slice get
	 * the same rigor as the full ENR-1 story would).
	 * @throws IllegalStateException if {@code paymentId} is not a confirmed
	 * payment in the current tenant context.
	 */
	void activateFromConfirmedPayment(UUID paymentId, UUID studentId, UUID courseId);

}
