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
 * Two production code paths deliberately call {@link #set(UUID)} directly
 * (rather than going through the normal request-scoped resolution this
 * class's javadoc above describes): {@code PaymentConfirmationService
 * #confirmByGatewayReference} (the payment-gateway webhook path, which has
 * no authenticated request/tenant context of its own to read at all) and
 * {@code PlatformAdminLedgerQueryService#enrichAcrossTenants} (the
 * Platform-Admin cross-tenant ledger dashboard, which enriches one tenant
 * group at a time on behalf of a Platform Admin caller who likewise has no
 * ambient tenant context). Both follow the same disciplined shape: {@code
 * set} inside a {@code try} block, {@code clear} in a {@code finally} block,
 * and the tenant id passed to {@code set} is always sourced from a trusted,
 * already-persisted DB row (the payment's own {@code tenantId} column; the
 * ledger entry's own {@code tenantId} column) - never from a client-supplied
 * request field. See each class's own javadoc for its specific rationale.
 * Any OTHER production code path introducing a new {@code set(UUID)} call
 * site should be held to this same standard. Test code also populates it
 * directly (see {@code TenantContextTestSupport}).
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
