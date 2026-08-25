package com.lms.paymentmanagement.support;

import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Shared "owner student, or staff holding {@code PAYMENTS_SLIPS}/{@code
 * VIEW}" read-access check, used identically by {@code order.service},
 * {@code payment.service}, and {@code slip.service} (the last of these
 * previously had its own byte-for-byte-identical {@code SlipAccessGuard},
 * since deleted as a Low review finding - a slip's owner student/staff-view
 * rule is not actually distinct from every other order-derived resource's
 * rule) - the exact access rule the plan's API contract (§10) specifies for
 * every order/payment/slip read endpoint. {@code refund.service} does NOT
 * use this guard - a refund is a mutation gated on its own {@code
 * PAYMENTS_SLIPS}/{@code APPROVE} permission check (see {@code
 * RefundService}), not this read-access rule. Kept in one place so the rule
 * (and its cross-tenant-vs-same-tenant status-code convention) is applied
 * consistently rather than re-implemented per service.
 *
 * <p><b>Status-code convention (mirrors {@code MaterialAccessGuard}'s
 * anti-enumeration precedent exactly):</b> a
 * cross-tenant id never even reaches this guard - the owning repository's
 * tenant-scoped {@code findById} already returns empty for it, which callers
 * turn into a 404 ({@code NotFoundException}) before calling this method at
 * all. A same-tenant STUDENT caller who is not the resource's owning student
 * also gets a 404 ({@link NotFoundException}), not a 403 - a Student must
 * never be able to distinguish "exists but isn't mine" from "doesn't exist"
 * (this guard is deliberately generic in its message, since it is shared
 * across order/payment/slip resource types and does not itself know which
 * one the caller is checking). A same-tenant staff caller who lacks the
 * required {@code PAYMENTS_SLIPS}/{@code VIEW} grant still gets 403 (via
 * {@link PermissionCheckService#requirePermission}) - staff already have
 * legitimate visibility into their own tenant's resource existence, so no
 * enumeration risk applies to them.
 */
@Component
public class PaymentDomainAccessGuard {

	private static final String STUDENT_ROLE = "STUDENT";

	private final PermissionCheckService permissionCheckService;

	public PaymentDomainAccessGuard(PermissionCheckService permissionCheckService) {
		this.permissionCheckService = permissionCheckService;
	}

	/**
	 * @param ownerStudentId the {@code student_id} that owns the resource
	 * being read (the order's, or the order behind the payment/refund being
	 * read).
	 */
	public void requireOwnerOrStaffView(UUID ownerStudentId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (STUDENT_ROLE.equals(principal.role())) {
			if (!principal.userId().equals(ownerStudentId)) {
				throw new NotFoundException("Resource not found");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
	}

}
