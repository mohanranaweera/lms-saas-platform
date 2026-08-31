package com.lms.identityaccessservice.api;

/**
 * Domain-level permission area, mirroring the 15 rows of
 * {@code docs/requirements/user-roles-and-permissions.md} §2's staff sub-role
 * domain-level permission matrix. Consumed by
 * {@link PermissionCheckService#hasPermission(DomainArea, PermissionAction)}
 * and future business-domain modules gating their controllers.
 */
public enum DomainArea {

	STUDENTS, TEACHERS, STAFF_AND_ROLES, COURSES, MATERIALS,

	/**
	 * A grant here (e.g. {@code CREATE_EDIT}, {@code APPROVE}) is a
	 * domain-level category grant only - it never authorizes mutating a
	 * terminal-state ({@code CONFIRMED}/{@code REJECTED}/{@code REFUNDED})
	 * payment or manual payment slip. {@code .claude/rules/payments.md} §1-§2
	 * governs those tables' actual mutation rules regardless of what {@link
	 * PermissionCheckService#hasPermission(DomainArea, PermissionAction)}
	 * returns for this area; a payments-module endpoint must enforce that
	 * separately, not treat this grant as sufficient authorization on its
	 * own.
	 */
	PAYMENTS_SLIPS,

	/**
	 * A grant here (e.g. {@code DELETE}) is a domain-level category grant
	 * only - it never authorizes a literal row delete on a ledger entry.
	 * {@code .claude/rules/payments.md} §4 requires ledger entries to be
	 * append-only (corrections are new rows, never {@code UPDATE}/{@code
	 * DELETE}); a finance/expense-module endpoint must enforce that
	 * separately, not treat this grant as sufficient authorization on its
	 * own.
	 */
	FINANCE_EXPENSES,

	ATTENDANCE, EXAMS, DEVICES,

	/**
	 * A grant here (e.g. {@code APPROVE}) is a domain-level category grant
	 * only - it never itself authorizes a direct {@code enrollment} write. A
	 * grant authorizes exactly one thing: the {@code reactivation_request}
	 * status transition (approve/reject) performed by {@code
	 * ReactivationRequestService}. Enrollment activation/reactivation is
	 * never decided by this permission result - it always requires the
	 * independent {@code PaymentStatusApi}/{@code SlipStatusApi}
	 * re-verification enforced inside {@code EnrollmentActivationApi}'s
	 * {@code activateFrom*}/{@code reactivateFrom*} methods, regardless of
	 * what {@link PermissionCheckService#hasPermission(DomainArea,
	 * PermissionAction)} returns for this area. Mirrors {@link
	 * #PAYMENTS_SLIPS}/{@link #FINANCE_EXPENSES}'s identical caveat, per
	 * {@code .claude/rules/payments.md} §8.
	 */
	ACCESS_EXPIRY,

	REVIEWS_MODERATION, AUDIT_LOG, BRANDING_SETTINGS, SUPPORT_TICKETS

}
