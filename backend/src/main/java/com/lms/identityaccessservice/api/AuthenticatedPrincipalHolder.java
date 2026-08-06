package com.lms.identityaccessservice.api;

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

}
