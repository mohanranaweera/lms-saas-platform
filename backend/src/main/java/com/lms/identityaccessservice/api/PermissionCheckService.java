package com.lms.identityaccessservice.api;

/**
 * The single contract other future business-domain modules (course-management,
 * payment-management, etc.) are permitted to depend on for permission checks -
 * never {@code PermissionCheckServiceImpl}, never {@code RoleCatalogRepository}/
 * {@code RoleCatalogEntry}, per {@code .claude/rules/architecture.md}'s "a
 * module may depend only on another module's {@code api} package" rule.
 *
 * <p><b>{@link DomainArea#PAYMENTS_SLIPS}/{@link DomainArea#FINANCE_EXPENSES}
 * callers, read this first:</b> a {@code true} result for those two areas is
 * a domain-level category grant only - it never authorizes a mutation that
 * {@code .claude/rules/payments.md} forbids (a literal ledger-row delete, or
 * mutating a terminal-state payment). See each constant's own javadoc on
 * {@link DomainArea} for the specific rule it must not be read as overriding.
 */
public interface PermissionCheckService {

	boolean hasPermission(DomainArea domainArea, PermissionAction action);

	/**
	 * String-typed overload for Spring Security {@code @PreAuthorize} SpEL,
	 * which can't easily reference Java enum constants. Parses both arguments
	 * case-sensitively against the enum names; an unrecognized or absent
	 * (null) value denies (returns {@code false}) rather than throwing, since
	 * this is called from a security expression where a thrown exception
	 * must not be relied on for denial.
	 */
	boolean hasPermission(String domainArea, String action);

	/**
	 * Throws {@link org.springframework.security.access.AccessDeniedException}
	 * if the check fails. For direct programmatic use outside
	 * {@code @PreAuthorize}.
	 */
	void requirePermission(DomainArea domainArea, PermissionAction action);

	/**
	 * STAFF-MGMT-02 §2.2: does the current actor hold {@code capability}?
	 * <ul>
	 * <li>Tenant Admin: {@code hasPermission(primary)} (plus the capability's static-role
	 * allowlist, if any) - unchanged access;
	 * <li>seeded staff sub-roles (STAFF-MGMT-03): the tenant's explicit grant/revoke for a
	 * capability configurable for the role, otherwise the same static default as Tenant Admin's rule;
	 * <li>Operator / Manager: ENFORCED, configurable for the role and granted by the tenant;
	 * <li>Teacher: teacher-configurable and not revoked by the tenant (default granted);
	 * <li>anyone else (Teacher Assistant, Student, Platform Admin, no tenant context): false.
	 * </ul>
	 * Tenant grants are read from the database once per request.
	 */
	boolean hasCapability(Capability capability);

	/** SpEL-safe overload: an unknown or null key denies ({@code false}), never throws. */
	boolean hasCapability(String capabilityKey);

	/** Throws {@link org.springframework.security.access.AccessDeniedException} unless {@link #hasCapability}. */
	void requireCapability(Capability capability);

	/** Every capability the current actor holds (empty for Platform Admin / unknown roles). */
	java.util.Set<Capability> effectiveCapabilities();

	/**
	 * STAFF-MGMT-02 §2.3 / STAFF-MGMT-03 §6.1: the interceptor decision for a
	 * {@code @RequiresCapability(capability)} handler and the current actor.
	 * <ul>
	 * <li>Operator / Manager: granted -&gt; ELEVATE, otherwise DENY;
	 * <li>seeded staff sub-role: explicit tenant grant -&gt; ELEVATE, explicit revoke -&gt; DENY,
	 * no override row -&gt; PASS (static matrix unchanged);
	 * <li>Teacher: a revoked teacher-configurable capability -&gt; DENY, otherwise PASS;
	 * <li>everyone else (Tenant Admin, Teacher Assistant, Student, Platform Admin): PASS.
	 * </ul>
	 */
	CapabilityGate capabilityGate(Capability capability);

	/**
	 * {@code true} when the current actor holds {@code (domainArea, action)} only through a
	 * delegated capability grant rather than the static matrix: always for Operator/Manager,
	 * and for a seeded sub-role whose static matrix lacks the pair but which holds it through this
	 * request's elevation. Used by guards that keep some sub-operations (course pricing edits,
	 * teacher commission, manual-payment adjustments) with the roles that statically own them.
	 */
	boolean isDelegated(DomainArea domainArea, PermissionAction action);

	/**
	 * Does a member with {@code roleCode} in the CURRENT trusted-context tenant hold
	 * {@code capability}, independent of the request principal? Used to re-validate a long-lived
	 * session's actor (e.g. a Log as Student support session) on every request. Fails closed
	 * without a resolved tenant for configurable roles; never accepts a tenant id.
	 */
	boolean roleHoldsCapability(String roleCode, Capability capability);

}
