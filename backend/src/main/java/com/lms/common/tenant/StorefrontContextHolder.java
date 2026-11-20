package com.lms.common.tenant;

import java.util.Optional;

/**
 * STORE-01 / ADR-037: request-scoped holder of the resolved {@link
 * StorefrontContext}. Empty on tenant hosts, custom domains and every path
 * {@code TenantResolutionFilter} does not filter. Only {@code
 * TenantResolutionFilter} calls {@link #set} in production, always paired
 * with {@link #clear()} in a {@code finally} block. Like {@link
 * TenantContextHolder} it is never inherited by background/async work.
 */
public final class StorefrontContextHolder {

	private static final ThreadLocal<StorefrontContext> CURRENT = new ThreadLocal<>();

	private StorefrontContextHolder() {
	}

	public static void set(StorefrontContext context) {
		if (context == null) {
			throw new IllegalArgumentException("context must not be null");
		}
		CURRENT.set(context);
	}

	/** The storefront of the current request, empty on a tenant host. */
	public static Optional<StorefrontContext> current() {
		return Optional.ofNullable(CURRENT.get());
	}

	public static boolean isSet() {
		return CURRENT.get() != null;
	}

	public static void clear() {
		CURRENT.remove();
	}

}
