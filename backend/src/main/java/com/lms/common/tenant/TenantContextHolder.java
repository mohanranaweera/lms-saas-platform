package com.lms.common.tenant;

import java.util.UUID;

/**
 * Thread-local holder backing {@link TenantContext}. {@code set}/{@code clear}
 * are deliberately static and explicit: background/async work does not
 * inherit a request thread's value automatically (per
 * docs/architecture/multi-tenancy.md ss1) and must call {@link #set(UUID)}
 * with a tenant id it carries explicitly in its own job/event payload, then
 * {@link #clear()} when done to avoid leaking state to a pooled thread's next
 * use.
 *
 * No production code path calls {@link #set(UUID)} yet - that is
 * identity-access-service's responsibility once it exists. Test code
 * populates it directly (see {@code TenantContextTestSupport}).
 */
public final class TenantContextHolder implements TenantContext {

	private static final ThreadLocal<UUID> CURRENT_TENANT = new ThreadLocal<>();

	@Override
	public UUID getTenantId() {
		UUID tenantId = CURRENT_TENANT.get();
		if (tenantId == null) {
			throw new TenantContextNotResolvedException();
		}
		return tenantId;
	}

	public static void set(UUID tenantId) {
		if (tenantId == null) {
			throw new IllegalArgumentException("tenantId must not be null");
		}
		CURRENT_TENANT.set(tenantId);
	}

	public static void clear() {
		CURRENT_TENANT.remove();
	}

	public static boolean isSet() {
		return CURRENT_TENANT.get() != null;
	}

}
