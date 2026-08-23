package com.lms.paymentmanagement.support;

import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * Shared "owner student, or staff holding {@code PAYMENTS_SLIPS}/{@code
 * VIEW}" read-access check, used identically by {@code order.service},
 * {@code payment.service}, and {@code refund.service} - the exact access
 * rule the plan's API contract (§10) specifies for every order/payment/
 * refund read endpoint. Kept in one place so the rule (and its
 * cross-tenant-vs-same-tenant status-code convention) is applied
 * consistently rather than re-implemented per service.
 *
 * <p>Status-code convention (mirrors {@code CourseAccessGuard}'s established
 * precedent exactly): a cross-tenant id never even reaches this guard - the
 * owning repository's tenant-scoped {@code findById} already returns empty
 * for it, which callers turn into a 404 ({@code NotFoundException}) before
 * calling this method at all. This guard only ever raises 403 ({@link
 * AccessDeniedException}), for a same-tenant caller who is neither the
 * resource's owning student nor a staff member holding the required grant.
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
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			return;
		}
		permissionCheckService.requirePermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW);
	}

}
