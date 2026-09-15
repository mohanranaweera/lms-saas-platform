package com.lms.identityaccessservice.api;

import org.springframework.security.access.AccessDeniedException;

/**
 * Thread-local holder for the current request's {@link AuthenticatedPrincipal},
 * mirroring {@code com.lms.common.tenant.TenantContextHolder} exactly:
 * {@code set}/{@code clear} are static and explicit, and reading before
 * population fails loudly rather than returning null. Only {@code
 * JwtAuthenticationFilter} calls {@link #set} in production code, always
 * paired with {@link #clear()} in a {@code finally} block.
 */
public final class AuthenticatedPrincipalHolder {

	private static final ThreadLocal<AuthenticatedPrincipal> CURRENT = new ThreadLocal<>();

	private AuthenticatedPrincipalHolder() {
	}

	public static void set(AuthenticatedPrincipal principal) {
		if (principal == null) {
			throw new IllegalArgumentException("principal must not be null");
		}
		CURRENT.set(principal);
	}

	public static AuthenticatedPrincipal get() {
		AuthenticatedPrincipal principal = CURRENT.get();
		if (principal == null) {
			throw new IllegalStateException(
					"AuthenticatedPrincipal has not been resolved for this thread. It is only populated by "
							+ "JwtAuthenticationFilter after a valid access token has been verified - never assume "
							+ "it is present without going through an authenticated request path.");
		}
		return principal;
	}

	public static void clear() {
		CURRENT.remove();
	}

	public static boolean isSet() {
		return CURRENT.get() != null;
	}

	/**
	 * Throws {@link AccessDeniedException} unless the current thread's
	 * resolved {@link AuthenticatedPrincipal#role()} exactly equals {@code
	 * role}. Added to de-duplicate the identical hand-rolled
	 * {@code requirePlatformAdmin()}/{@code PLATFORM_ADMIN_ROLE} check
	 * previously copy-pasted across {@code TenantApprovalService}, {@code
	 * PlatformAdminLedgerQueryService}, and {@code
	 * PlatformAdminAuditLogQueryService} - callers should invoke this instead
	 * of hand-rolling their own role string comparison. This is a defensive,
	 * service-layer re-confirmation of a role already enforced by each
	 * caller's own {@code @PreAuthorize}, never the sole authorization gate.
	 */
	public static void requireRole(String role) {
		if (!get().role().equals(role)) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
	}

}
