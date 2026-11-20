package com.lms.identityaccessservice.service;

import com.lms.identityaccessservice.api.PermissionPair;
import java.util.Set;

/**
 * STAFF-MGMT-02 §2.3: the {@link PermissionPair}s elevated for the CURRENT request of an
 * Operator/Manager holding the tenant grant behind the handler's {@code @RequiresCapability}.
 * A plain (non-inheritable) thread-local: set only by {@code CapabilityEnforcementInterceptor}
 * after its checks pass and cleared in its {@code afterCompletion}, so an elevation can never
 * reach another request or a worker thread.
 */
public final class CapabilityElevationHolder {

	private static final ThreadLocal<Set<PermissionPair>> CURRENT = new ThreadLocal<>();

	private CapabilityElevationHolder() {
	}

	public static void set(Set<PermissionPair> pairs) {
		CURRENT.set(Set.copyOf(pairs));
	}

	public static Set<PermissionPair> get() {
		Set<PermissionPair> pairs = CURRENT.get();
		return (pairs == null) ? Set.of() : pairs;
	}

	public static void clear() {
		CURRENT.remove();
	}

}
