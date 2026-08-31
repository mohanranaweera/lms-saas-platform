package com.lms.enrollmentmanagement.api;

import java.util.List;

/**
 * A narrow, read-only, platform-wide diagnostic. Lists every {@code
 * enrollment} row (current or historical - lineage rows are never excluded)
 * whose non-null {@code activating_payment_id} does not resolve to a {@code
 * CONFIRMED} payment, or whose non-null {@code activating_slip_id} does not
 * resolve to an {@code APPROVED} payment slip - including the case where the
 * referenced payment/slip row cannot be found at all. In normal operation
 * this should always be empty: {@code EnrollmentActivationApi}'s activation
 * and reactivation paths only ever write an {@code enrollment} row inside
 * the SAME transaction as the confirming payment/slip write (see {@code
 * EnrollmentActivationApi}'s "Transactional-boundary contract" javadoc), so
 * a non-empty result here indicates either a genuine bug or direct data
 * tampering, not an expected/accepted race window - see this interface's
 * one method's own javadoc for what a human should do with a non-empty
 * result.
 *
 * <h2>Placement: {@code enrollment-management}, not {@code payment-management}</h2>
 * {@code enrollment} is this module's own table - reading it (via {@link
 * com.lms.enrollmentmanagement.repository.EnrollmentRepository}) is an
 * ordinary, fully legitimate same-module read, never a cross-domain
 * boundary crossing. Cross-checking each flagged row's evidence against its
 * actual terminal status goes through the existing, already-approved,
 * single-id {@code PaymentStatusApi#isConfirmedForCurrentTenant}/{@code
 * SlipStatusApi#isApprovedForCurrentTenant} - the same {@code api}-package
 * contract {@code EnrollmentActivationService} already depends on - rather
 * than a raw SQL statement joining {@code payment-management}'s tables
 * directly, per {@code .claude/rules/architecture.md}'s "a module may depend
 * only on another module's {@code api} package" rule. This deliberately
 * costs one cross-module call per flagged row (this is a low-frequency ops
 * diagnostic, not a request-time hot path, so the N+1-shaped cost is an
 * acceptable trade-off for staying inside the architecture's module-boundary
 * rules) instead of growing {@code PaymentStatusApi}/{@code SlipStatusApi}
 * with a new batch/cross-tenant variant whose only caller would be this one
 * diagnostic.
 *
 * <h2>Read-only, platform-wide, deliberately unwired</h2>
 * This performs no writes to {@code enrollment}/{@code payment}/{@code
 * payment_slip} and is deliberately cross-tenant (mirroring {@code
 * PaymentRepository}'s {@code ...AcrossTenants} convention) - a platform ops
 * diagnostic, not a tenant self-service read, so it is never reachable from
 * any authenticated-tenant-user-facing controller. This module ships NO
 * {@code @Scheduled} job (this codebase has no scheduling infrastructure at
 * all, and adding one is an explicit future decision, not bundled here) and
 * NO admin endpoint/controller/UI wiring this up - it is an injectable
 * {@code @Service} bean with no current production caller, invokable
 * manually (a future admin tool, a test, or a debugger session) exactly like
 * a diagnostic library method. This method never self-corrects anything.
 */
public interface EnrollmentReconciliationApi {

	/**
	 * @return every {@code OrphanedEnrollmentEvidence} row found across every
	 * tenant on the platform, in no particular order. An empty list is the
	 * expected, structurally-guaranteed steady state (see class javadoc). A
	 * non-empty result is a genuine incident requiring manual investigation
	 * (most likely: manually confirm/reject the orphaned payment/slip to
	 * match the enrollment's actual state, mirroring how any other detected
	 * inconsistency in this codebase is handled) - not a code path that
	 * self-corrects automatically.
	 */
	List<OrphanedEnrollmentEvidence> findEnrollmentsWithUnconfirmedActivationEvidenceAcrossTenants();

}
