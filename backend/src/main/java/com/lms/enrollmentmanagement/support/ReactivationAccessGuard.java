package com.lms.enrollmentmanagement.support;

import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared "owner student, or staff holding {@code ACCESS_EXPIRY}/{@code
 * VIEW}" read-access check for this domain - mirrors {@code
 * paymentmanagement.support.PaymentDomainAccessGuard}'s exact shape and
 * status-code convention, but is intentionally a SEPARATE, domain-local
 * class rather than a reused import: {@code PaymentDomainAccessGuard} lives
 * in {@code paymentmanagement.support} and is checked against {@code
 * PAYMENTS_SLIPS}, not {@code ACCESS_EXPIRY}, and per {@code
 * .claude/rules/architecture.md} a module must not reach into another
 * domain's non-{@code api} package. This is the same acceptable duplication
 * {@code PaymentDomainAccessGuard}'s own javadoc describes (a slip-specific
 * guard was deleted in favor of one shared payment-domain guard) applied
 * across a DIFFERENT domain boundary, not a repeat of the same mistake.
 *
 * <p><b>Status-code convention (identical to {@code
 * PaymentDomainAccessGuard}):</b> a cross-tenant id never even reaches this
 * guard - the owning repository's tenant-scoped {@code findById} already
 * returns empty for it, which callers turn into a 404 ({@link
 * NotFoundException}) before calling this method at all. A same-tenant
 * STUDENT caller who is not the resource's owning student also gets a 404,
 * not a 403 - a Student must never be able to distinguish "exists but isn't
 * mine" from "doesn't exist". A same-tenant staff caller who lacks {@code
 * ACCESS_EXPIRY}/{@code VIEW} gets 403 (via {@link
 * PermissionCheckService#requirePermission}).
 */
@Component
public class ReactivationAccessGuard {

	private static final String STUDENT_ROLE = "STUDENT";

	private final PermissionCheckService permissionCheckService;

	public ReactivationAccessGuard(PermissionCheckService permissionCheckService) {
		this.permissionCheckService = permissionCheckService;
	}

	/** @param ownerStudentId the {@code student_id} that owns the resource being read. */
	public void requireOwnerOrStaffView(UUID ownerStudentId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (STUDENT_ROLE.equals(principal.role())) {
			if (!principal.userId().equals(ownerStudentId)) {
				throw new NotFoundException("Resource not found");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW);
	}

}
