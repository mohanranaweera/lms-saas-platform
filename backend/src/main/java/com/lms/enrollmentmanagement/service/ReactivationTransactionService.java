package com.lms.enrollmentmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.enrollmentmanagement.domain.Enrollment;
import com.lms.enrollmentmanagement.domain.ReactivationRequest;
import com.lms.enrollmentmanagement.repository.EnrollmentRepository;
import com.lms.enrollmentmanagement.repository.ReactivationRequestRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Internal collaborator of {@link EnrollmentActivationService} - NOT exposed
 * via this module's {@code api} package, never injected by another domain
 * (enforced by this class itself being package-private, not just by
 * convention - MVP-012 review finding M1). Owns exactly the "look up the
 * prior current row + its matching {@code APPROVED} reactivation request,
 * supersede the prior row, insert the new one" logic for both the
 * confirmed-payment and approved-slip reactivation paths.
 *
 * <h2>Why this class carries NO {@code @Transactional} annotation at all (not even {@code REQUIRED})</h2>
 * An earlier revision of this class ran this logic in its own, independent
 * {@code Propagation.REQUIRES_NEW} transaction, to fix a real bug: a refusal
 * here (no current enrollment / no matching {@code APPROVED} request) threw
 * an {@link IllegalStateException} that, at the time, propagated uncaught out
 * through the caller's ({@code PaymentConfirmationService}/{@code
 * SlipReviewService}) own {@code @Transactional} method, marking THAT
 * transaction rollback-only - losing the payment/slip's own already-{@code
 * CONFIRMED}/{@code APPROVED} write and its ledger entry / audit log row too,
 * contradicting plan §13's explicit requirement ("payment stays CONFIRMED...
 * logged as an ops-visible inconsistency, not silently swallowed").
 *
 * <p>A post-implementation review (security/database/architecture/QA)
 * flagged that fix's trade-off as a real, separate gap: because a {@code
 * REQUIRES_NEW} transaction commits independently of, and before, the
 * caller's own transaction, a LATER, unrelated failure in the caller (after
 * this method returned successfully but before the caller itself committed)
 * would leave a durably reactivated {@code enrollment} row paired with a
 * payment/slip confirmation that never itself reached its terminal state -
 * the exact reverse of {@code .claude/rules/backend.md}'s payment-activation
 * atomicity rule ("enrollment must never activate without a persisted,
 * confirmed payment record").
 *
 * <p><b>First attempted fix (reverted) and why it broke the original bug's fix:</b> simply
 * changing this class's propagation to plain {@code @Transactional} (default {@code REQUIRED},
 * joining the caller's ambient transaction instead of opening a new one) looked correct - the
 * caller already wraps its call to this class in a {@code catch (IllegalStateException)}. But
 * Spring's {@code TransactionInterceptor} marks a transaction rollback-only the moment an
 * exception escapes ANY {@code @Transactional}-annotated method's own proxy boundary -
 * regardless of propagation type, and regardless of whether some OUTER caller further up the
 * stack eventually catches it. Since the two {@code .orElseThrow(...)} calls below live inside
 * a method that was still marked {@code @Transactional}, a refusal's {@link
 * IllegalStateException} crossed THIS method's own boundary first, marking the (now shared,
 * REQUIRED-joined) ambient transaction rollback-only BEFORE the caller's catch block ever ran -
 * so the caller's later commit attempt failed with {@code UnexpectedRollbackException} even
 * though the caller itself never saw an uncaught exception. This reintroduced the exact original
 * bug (verified by a real Testcontainers run: {@code
 * PaymentConfirmationReactivationRefusalIntegrationTest}/{@code
 * SlipApprovalReactivationRefusalIntegrationTest} started failing with 500s where they expect
 * 200).
 *
 * <p><b>Actual resolution (this revision):</b> this class carries NO {@code @Transactional}
 * annotation on either method - not {@code REQUIRES_NEW}, not plain {@code @Transactional}
 * either. With no annotation, Spring wraps this method in no transactional advice of its own; it
 * simply executes as an ordinary Java method call inside whatever transaction the caller already
 * has open (repository {@code save}/{@code saveAndFlush} calls still participate in - and commit
 * atomically with - that ambient transaction, since Spring Data JPA repository methods are
 * themselves transactional and join the already-active one). A refusal's {@link
 * IllegalStateException} therefore crosses NO {@code @Transactional} boundary until it reaches
 * the caller's own {@code @Transactional} method ({@code
 * PaymentConfirmationService#confirmByGatewayReference}/{@code SlipReviewService#approve}),
 * where it is caught entirely INSIDE that method's own body - never propagating past ITS
 * boundary either - so Spring never marks anything rollback-only for a refusal. A SUCCESS's
 * {@code enrollment} mutation, having never opened its own separate transaction, commits only
 * when the caller's own transaction commits - making activation evidence and enrollment access
 * genuinely atomic, exactly as {@code .claude/rules/backend.md}'s payment-activation atomicity
 * rule requires.
 *
 * <h2>The one thing {@code REQUIRES_NEW} WAS protecting against, and why it's covered now</h2>
 * Unlike a plain {@link IllegalStateException}, the {@link
 * DataIntegrityViolationException} this method's own race-guard catches
 * (below) IS a genuine Postgres statement failure - and Postgres aborts an
 * entire transaction on any statement error unless a savepoint is used
 * ({@code Propagation.NESTED}, confirmed empirically unworkable with this
 * codebase's {@code JpaTransactionManager}/Hibernate stack: {@code
 * HibernateJpaDialect#beginTransaction(...)} never returns an object
 * implementing Spring's {@code SavepointManager}, so {@code
 * NestedTransactionNotSupportedException} is unavoidable without a custom,
 * Spring-unsupported {@code JpaDialect}). A genuine constraint violation
 * reaching Postgres aborts the shared ambient transaction at the DB level
 * regardless of this class's own {@code @Transactional} annotation (or lack
 * of one) - catching it here only prevents SPRING from separately marking
 * anything rollback-only; it cannot un-abort an already-aborted Postgres
 * transaction. Two independent changes shipped alongside this one make that
 * race structurally unreachable in normal operation, so this class's own
 * {@code DataIntegrityViolationException} catch is now a defense-in-depth
 * backstop rather than the load-bearing mechanism it used to be:
 * <ul>
 * <li>{@code PaymentConfirmationService#confirmByGatewayReference}/{@code
 * SlipReviewService#approve} already take a {@code PESSIMISTIC_WRITE} lock on
 * the payment/slip row before reaching this code path, so two concurrent
 * deliveries of the SAME evidence (a retried webhook/approval) fully
 * serialize before either can reach this method - the second delivery
 * observes the already-terminal payment/slip status and returns via the
 * idempotent no-op path long before any {@code enrollment} row is touched.</li>
 * <li>{@code uq_reactivation_request_tenant_enrollment_live} (V24) now
 * schema-enforces "at most one request per enrollment that could still
 * result in a future order" - closing the gap where two DIFFERENT orders
 * could each end up linked to a live reactivation request for the same
 * enrollment. Combined with the point above, the only remaining path into
 * this method's supersede+insert is exactly one uniquely-linked order's
 * confirming payment/slip.</li>
 * </ul>
 * The {@link DataIntegrityViolationException} catch below is kept as a
 * defense-in-depth backstop, not the primary safety mechanism it used to be:
 * if it is ever genuinely hit despite the above (e.g. a future regression
 * reintroduces a race), the whole ambient transaction aborts and the
 * caller's own webhook/approval-retry semantics safely recover on
 * redelivery - a fail-safe outcome, not a silent corruption.
 */
@Service
class ReactivationTransactionService {

	private final EnrollmentRepository enrollmentRepository;

	private final ReactivationRequestRepository reactivationRequestRepository;

	private final TenantContext tenantContext;

	ReactivationTransactionService(EnrollmentRepository enrollmentRepository,
			ReactivationRequestRepository reactivationRequestRepository, TenantContext tenantContext) {
		this.enrollmentRepository = enrollmentRepository;
		this.reactivationRequestRepository = reactivationRequestRepository;
		this.tenantContext = tenantContext;
	}

	public void reactivateFromConfirmedPayment(UUID paymentId, UUID orderId, UUID studentId, UUID courseId,
			Instant accessExpiresAt) {
		if (enrollmentRepository.existsByActivatingPaymentId(paymentId)) {
			// Already reactivated via this exact payment (a retried
			// webhook) - checked BEFORE touching the prior row, so a retry
			// never risks superseding a row a second time with no matching
			// new row.
			return;
		}

		Enrollment priorEnrollment = enrollmentRepository.findCurrentByStudentIdAndCourseId(studentId, courseId)
			.orElseThrow(() -> new IllegalStateException("Refusing to reactivate enrollment: no current enrollment "
					+ "row exists for student " + studentId + " / course " + courseId + " - structurally "
					+ "unreachable given OrderService's order-creation gate, but re-verified anyway"));
		ReactivationRequest request = reactivationRequestRepository
			.findApprovedByEnrollmentIdAndNewOrderId(priorEnrollment.getId(), orderId)
			.orElseThrow(() -> new IllegalStateException(
					"Refusing to reactivate enrollment: no APPROVED reactivation request linked to order " + orderId
							+ " exists for enrollment " + priorEnrollment.getId()
							+ " - either no reactivation request resolves at all, or the request approved for this "
							+ "enrollment is linked to a DIFFERENT order than the one confirming (defense-in-depth "
							+ "cross-check) - structurally unreachable given OrderService's order-creation gate and "
							+ "ReactivationRequestService's at-most-one-live-request guard, but re-verified anyway"));

		try {
			priorEnrollment.supersede();
			// saveAndFlush (not save) is load-bearing here: Hibernate's flush
			// plan always executes pending INSERTs before pending UPDATEs
			// within one flush, regardless of call order, so a plain save()
			// here would let the new row's INSERT below reach Postgres before
			// this UPDATE - briefly violating
			// uq_enrollment_tenant_student_course_current (V22), since two
			// rows would momentarily both have superseded_at IS NULL for the
			// same (tenant_id, student_id, course_id). Forcing an explicit
			// flush of the UPDATE first guarantees the INSERT below is the
			// only one visible to that partial unique index at insert time.
			enrollmentRepository.saveAndFlush(priorEnrollment);
			Enrollment newEnrollment = Enrollment.reactivatedFromConfirmedPayment(tenantContext.getTenantId(),
					studentId, courseId, paymentId, accessExpiresAt, priorEnrollment.getId());
			// saveAndFlush (not save) is ALSO load-bearing on this second
			// call (MVP-012 review finding H2): without an explicit flush
			// here, a genuine constraint violation from a real concurrent
			// race would not surface until the NEXT auto-flush point
			// (commonly commit-time), by which point it is OUTSIDE this
			// try/catch and uncaught - silently defeating the race-guard
			// below. Flushing explicitly guarantees the violation - if any -
			// is raised and caught right here.
			enrollmentRepository.saveAndFlush(newEnrollment);
		}
		catch (DataIntegrityViolationException ex) {
			// Defense-in-depth only - see class javadoc for why this branch
			// should now be structurally unreachable in normal operation. If
			// it is ever hit anyway, this catch still swallows it (treating
			// it as "lost a race, someone else already reactivated this
			// lineage"), but because this method now runs in the caller's
			// ambient transaction, Postgres has already aborted that whole
			// transaction at the statement level - the caller's own
			// subsequent commit attempt will fail and its webhook/approval
			// retry semantics will safely recover on redelivery, rather than
			// this silently "succeeding" while the caller's write is lost.
		}
		// request itself is not mutated further here - it was already
		// marked fulfilled implicitly via its newOrderId being set at
		// order-creation time (ReactivationLinkingApi).
	}

	public void reactivateFromApprovedSlip(UUID slipId, UUID orderId, UUID studentId, UUID courseId,
			Instant accessExpiresAt) {
		if (enrollmentRepository.existsByActivatingSlipId(slipId)) {
			// Already reactivated via this exact slip (a retried approval
			// call) - checked BEFORE touching the prior row.
			return;
		}

		Enrollment priorEnrollment = enrollmentRepository.findCurrentByStudentIdAndCourseId(studentId, courseId)
			.orElseThrow(() -> new IllegalStateException("Refusing to reactivate enrollment: no current enrollment "
					+ "row exists for student " + studentId + " / course " + courseId + " - structurally "
					+ "unreachable given OrderService's order-creation gate, but re-verified anyway"));
		ReactivationRequest request = reactivationRequestRepository
			.findApprovedByEnrollmentIdAndNewOrderId(priorEnrollment.getId(), orderId)
			.orElseThrow(() -> new IllegalStateException(
					"Refusing to reactivate enrollment: no APPROVED reactivation request linked to order " + orderId
							+ " exists for enrollment " + priorEnrollment.getId()
							+ " - either no reactivation request resolves at all, or the request approved for this "
							+ "enrollment is linked to a DIFFERENT order than the one confirming (defense-in-depth "
							+ "cross-check) - structurally unreachable given OrderService's order-creation gate and "
							+ "ReactivationRequestService's at-most-one-live-request guard, but re-verified anyway"));

		try {
			priorEnrollment.supersede();
			// saveAndFlush is load-bearing here - see the identical comment in
			// reactivateFromConfirmedPayment above.
			enrollmentRepository.saveAndFlush(priorEnrollment);
			Enrollment newEnrollment = Enrollment.reactivatedFromApprovedSlip(tenantContext.getTenantId(), studentId,
					courseId, slipId, accessExpiresAt, priorEnrollment.getId());
			// saveAndFlush (finding H2) - see the identical comment in
			// reactivateFromConfirmedPayment above.
			enrollmentRepository.saveAndFlush(newEnrollment);
		}
		catch (DataIntegrityViolationException ex) {
			// See reactivateFromConfirmedPayment's identical catch block.
		}
	}

}
