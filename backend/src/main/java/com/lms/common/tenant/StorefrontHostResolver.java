package com.lms.common.tenant;

import java.util.UUID;

/**
 * STORE-01 / ADR-037 decision 2: the port {@code TenantResolutionFilter}
 * (identity-access-service) uses to resolve the storefront label of a
 * storefront-shaped host. Declared here, in {@code common}, so the
 * foundational identity module never depends on the business module that
 * implements it ({@code user-management}).
 *
 * <p>Called only AFTER the tenant is resolved and {@link TenantContextHolder}
 * is set; implementations are tenant-scoped by that context and never pick a
 * tenant themselves.
 */
public interface StorefrontHostResolver {

	/**
	 * An ENABLED storefront's current slug is {@code FOUND}; a retired slug of
	 * an enabled storefront is {@code MOVED} (with the current host); anything
	 * else (unknown, disabled, retired slug of a disabled storefront) is
	 * {@code NOT_FOUND}.
	 */
	HostLookup resolveForHost(String slug);

	enum HostStatus {

		FOUND, MOVED, NOT_FOUND

	}

	/**
	 * @param storefrontId set when FOUND
	 * @param teacherId set when FOUND ({@code tenant_user.id})
	 * @param slug the current slug (FOUND, MOVED)
	 * @param movedToHost the current host, set when MOVED
	 */
	record HostLookup(HostStatus status, UUID storefrontId, UUID teacherId, String slug, String movedToHost) {

		public static HostLookup notFound() {
			return new HostLookup(HostStatus.NOT_FOUND, null, null, null, null);
		}

	}

}
