package com.lms.auditlogmanagement.support;

import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;

/**
 * Shared, MVP-specific access restriction for every audit-log read path in
 * this module - both {@code AuditLogQueryService#search} (the general Audit
 * Log Viewer, AUDIT-3) and {@code AuditLogService#findForTarget} (the
 * per-student/per-teacher Activity tab, Wave 3). Extracted here (per {@code
 * .claude/rules/architecture.md}'s explicitly-permitted {@code support}
 * package: "owner/access-guard classes shared across a domain's own service
 * classes") so both read paths enforce the exact same allowlist through one
 * definition, rather than two independently-maintained copies that could
 * silently drift apart.
 *
 * <p>Plan §21 decision 1, option B (product-owner approved): the coarse
 * {@code DomainArea#AUDIT_LOG}/{@code PermissionAction#VIEW} grant in {@code
 * PermissionCheckServiceImpl}'s matrix is currently held by every staff
 * sub-role (each transcribed as "V (own-area actions)"/"V (full)" for the
 * auditor), which would - without a narrower gate - over-expose refund
 * amounts, actor identities, and material-deletion/enrollment/device-reset
 * history to every staff sub-role before any "own-area" scoping is ever
 * defined. This is a deliberate, interim restriction narrower than the
 * generic grant, not a redundant duplicate of it: every caller of {@link
 * #requireViewerRole()} still calls {@code
 * PermissionCheckService#requirePermission} first (the existing, unchanged
 * mechanism), then additionally requires the caller's actual role be one of
 * these two - Tenant Admin or Read-only Auditor - via this explicit
 * allowlist.
 *
 * <p>Compared directly as a raw string (rather than importing {@code
 * com.lms.identityaccessservice.domain.Role}) because that enum lives in
 * {@code identityaccessservice}'s {@code domain} package, not its {@code
 * api} package - {@code .claude/rules/architecture.md} forbids any other
 * module depending on a foreign domain's {@code domain} classes. {@link
 * com.lms.identityaccessservice.api.AuthenticatedPrincipal#role()} is
 * already the same live, server-re-read role string {@code
 * PermissionCheckServiceImpl.hasPermission} itself parses with {@code
 * Role.valueOf} - an unrecognized/absent value here denies exactly like that
 * method's own catch block, without this module importing the enum.
 */
public final class AuditViewerAccessGuard {

	private static final Set<String> VIEWER_ALLOWED_ROLES = Set.of("TENANT_ADMIN", "READ_ONLY_AUDITOR");

	private AuditViewerAccessGuard() {
	}

	/**
	 * Throws {@link AccessDeniedException} unless the caller's live,
	 * server-re-read role is {@code TENANT_ADMIN} or {@code READ_ONLY_AUDITOR}.
	 * Callers must invoke this only after already calling {@code
	 * PermissionCheckService#requirePermission(DomainArea#AUDIT_LOG,
	 * PermissionAction#VIEW)} - this method is the narrower, additional gate,
	 * never a substitute for the coarse grant check.
	 */
	public static void requireViewerRole() {
		String role = AuthenticatedPrincipalHolder.get().role();
		if (role == null || !VIEWER_ALLOWED_ROLES.contains(role)) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
	}

}
