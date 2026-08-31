package com.lms.enrollmentmanagement.api;

import java.util.UUID;

/**
 * The only contract other domains (namely {@code payment-management}, both
 * for its gateway-confirmation path and, since MVP-011, its manual-slip
 * path) are permitted to depend on for enrollment activation. Per plan §15,
 * only two code paths may ever call this, structurally: a verified webhook
 * confirming a payment, and a slip reaching {@code APPROVED} via authorized
 * reviewer action. No endpoint - checkout, redirect-return handler,
 * order-status endpoint, or admin tooling - may accept a client-reported
 * "payment succeeded" payload as activation evidence.
 */
public interface EnrollmentActivationApi {

	/**
	 * Idempotent via {@code uq_enrollment_tenant_student_course_current} (V22) - a
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

	/**
	 * The MVP-011 manual-slip counterpart to {@link
	 * #activateFromConfirmedPayment}. Idempotent via the same {@code
	 * uq_enrollment_tenant_student_course} (V19) constraint. Before writing
	 * the {@code enrollment} row, independently re-verifies via {@code
	 * SlipStatusApi} that {@code slipId} is genuinely {@code APPROVED} for
	 * the current tenant - never trusts the calling {@code
	 * SlipReviewService}'s claim alone (same defense-in-depth rationale as
	 * the confirmed-payment path above).
	 * @throws IllegalStateException if {@code slipId} is not an approved
	 * payment slip in the current tenant context.
	 */
	void activateFromApprovedSlip(UUID slipId, UUID studentId, UUID courseId);

	/**
	 * The reactivation counterpart of {@link #activateFromConfirmedPayment}
	 * (MVP-012/ADR-013) - NOT a third class of activation evidence, only a
	 * lineage-aware wrapper around the same confirmed-payment evidence type.
	 * Mirrors {@link #activateFromConfirmedPayment}'s independent {@code
	 * PaymentStatusApi} re-verification discipline exactly, then
	 * additionally: looks up the current ({@code supersededAt IS NULL})
	 * enrollment row for (studentId, courseId) and the {@code APPROVED}
	 * reactivation request whose {@code newOrderId} is EXACTLY {@code
	 * orderId} (never "the most recently reviewed approved+linked request
	 * for this enrollment", which could match the wrong request if more than
	 * one exists - the defense-in-depth cross-check this parameter exists
	 * for); if either does not resolve, refuses to activate (a legitimate,
	 * logged edge case for ops follow-up per plan §13 - the payment stays
	 * {@code CONFIRMED}, but no enrollment/access change happens - never a
	 * silent partial activation). Otherwise, in one atomic transaction: marks
	 * the prior row superseded and inserts a new, current row linked back to
	 * it.
	 *
	 * <h2>Transactional-boundary contract</h2>
	 * Neither this method's implementation, nor the internal {@code
	 * ReactivationTransactionService} collaborator it delegates the actual
	 * {@code enrollment} mutation to, carries its OWN {@code @Transactional}
	 * annotation (see {@code ReactivationTransactionService}'s class javadoc,
	 * including a documented, reverted attempt to add one). With no
	 * annotation anywhere in this call chain, everything below this method -
	 * the independent {@code PaymentStatusApi} re-verification AND the actual
	 * {@code enrollment} mutation - executes as ordinary Java calls inside
	 * the CALLER's ({@code PaymentConfirmationService#confirmByGatewayReference}/
	 * {@code SlipReviewService#approve}) single already-open transaction. This
	 * makes activation evidence and enrollment access genuinely atomic, per
	 * {@code .claude/rules/backend.md}'s payment-activation atomicity rule: a
	 * later, unrelated failure anywhere in the caller's transaction rolls the
	 * reactivation back too, and a successful reactivation can never commit
	 * durably while its confirming payment/slip write does not.
	 *
	 * <p>A refusal here ({@link IllegalStateException}) crosses NO {@code
	 * @Transactional} proxy boundary on its way up (there is none between
	 * where it's thrown and the caller), so it reaches the caller's own
	 * {@code catch (IllegalStateException)} - logged as an ops-visible
	 * structured warning, letting the caller's transaction continue and
	 * commit normally, per plan §13's explicit requirement ("payment stays
	 * CONFIRMED... logged as an ops-visible inconsistency, not silently
	 * swallowed") - WITHOUT Spring ever marking that transaction
	 * rollback-only. This is precisely why no method in this chain may carry
	 * its own {@code @Transactional} annotation: doing so (even with default
	 * {@code REQUIRED} propagation, joining rather than replacing the
	 * ambient transaction) would give Spring's {@code TransactionInterceptor}
	 * a boundary to see the refusal escape, marking the shared transaction
	 * rollback-only before the caller's catch ever runs - verified
	 * empirically as a real regression during this design's own review.
	 *
	 * <p>Idempotent: a retried call for a payment that has already produced
	 * a reactivated row is a no-op, checked BEFORE any mutation (never
	 * "supersede first, then discover the insert already happened").
	 * @param orderId the id of the order that owns the confirming {@code
	 * paymentId} - used to unambiguously resolve the one reactivation
	 * request this confirmation may legitimately fulfil.
	 * @throws IllegalStateException if {@code paymentId} is not a confirmed
	 * payment in the current tenant context, or if no current enrollment
	 * resolves for (studentId, courseId), or if no {@code APPROVED}
	 * reactivation request linked to {@code orderId} resolves for that
	 * enrollment - all structurally unreachable given {@code OrderService}'s
	 * order-creation gate, but re-verified anyway.
	 */
	void reactivateFromConfirmedPayment(UUID paymentId, UUID orderId, UUID studentId, UUID courseId);

	/**
	 * The reactivation counterpart of {@link #activateFromApprovedSlip} -
	 * mirrors {@link #reactivateFromConfirmedPayment} exactly (including its
	 * transactional-boundary contract), against {@code SlipStatusApi}
	 * instead of {@code PaymentStatusApi}.
	 * @param orderId the id of the order that owns the confirming {@code
	 * slipId}.
	 * @throws IllegalStateException if {@code slipId} is not an approved
	 * payment slip in the current tenant context, or if no current
	 * enrollment resolves for (studentId, courseId), or if no {@code
	 * APPROVED} reactivation request linked to {@code orderId} resolves for
	 * that enrollment.
	 */
	void reactivateFromApprovedSlip(UUID slipId, UUID orderId, UUID studentId, UUID courseId);

	/**
	 * Consolidated activate-or-reactivate entry point for the
	 * confirmed-payment evidence type (MVP-012 review finding M2) - the
	 * single place {@code PaymentConfirmationService#confirmByGatewayReference}
	 * calls, replacing what used to be that call site's own independent
	 * {@code EnrollmentAccessApi#resolveAccessState}-based branch between
	 * {@link #activateFromConfirmedPayment} and {@link
	 * #reactivateFromConfirmedPayment} (duplicated near-identically at {@code
	 * SlipReviewService#approve} - see {@link #activateOrReactivateFromApprovedSlip}).
	 *
	 * <p>Resolves the CURRENT access state for (studentId, courseId) and
	 * calls {@link #activateFromConfirmedPayment} for a brand-new ({@code
	 * NEVER_ENROLLED}) enrollment, or {@link #reactivateFromConfirmedPayment}
	 * otherwise. A refusal from EITHER branch is deliberately left to
	 * propagate as {@link IllegalStateException} out of this method - the
	 * caller is expected to wrap this ONE call in a single {@code catch
	 * (IllegalStateException)} (logging an {@code enrollment.reactivation_refused}
	 * structured warning with its own actor/id fields, per finding L1) rather
	 * than duplicating the branch decision itself. This intentionally treats
	 * a (structurally near-unreachable, defense-in-depth-only) verification
	 * failure on the first-time-activation branch the same way as a
	 * reactivation refusal - both are "payment/slip evidence was genuinely
	 * valid, but this specific enrollment/access mutation could not be
	 * safely applied," which plan §13's philosophy already treats as an
	 * ops-visible anomaly to log and move on from, not a reason to roll back
	 * a real, confirmed payment.
	 * @param orderId the id of the order that owns the confirming {@code
	 * paymentId}.
	 */
	void activateOrReactivateFromConfirmedPayment(UUID paymentId, UUID orderId, UUID studentId, UUID courseId);

	/**
	 * The manual-slip counterpart of {@link #activateOrReactivateFromConfirmedPayment} -
	 * mirrors it exactly, against {@link #activateFromApprovedSlip}/{@link
	 * #reactivateFromApprovedSlip}.
	 * @param orderId the id of the order that owns the confirming {@code
	 * slipId}.
	 */
	void activateOrReactivateFromApprovedSlip(UUID slipId, UUID orderId, UUID studentId, UUID courseId);

}
