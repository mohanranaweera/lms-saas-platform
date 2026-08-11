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

}
