package com.lms.identityaccessservice.api;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Construction helper for {@link Capability} constants (kept out of the enum so the constants
 * read as a flat table). Package-private: callers use {@link Capability}'s accessors.
 */
final class CapabilitySpec {

	static final String TENANT_ADMIN_ONLY = "Tenant Admin only";

	final CapabilityAvailability availability;

	final String reason;

	final Set<ConfigurableRole> configurableFor;

	final PermissionPair primary;

	final Set<PermissionPair> implied;

	final Set<String> staticRoleAllowlist;

	private CapabilitySpec(CapabilityAvailability availability, String reason, Set<ConfigurableRole> configurableFor,
			PermissionPair primary, PermissionPair[] implied, Set<String> staticRoleAllowlist) {
		this.availability = availability;
		this.reason = reason;
		this.configurableFor = configurableFor.isEmpty() ? Set.of() : Set.copyOf(configurableFor);
		this.primary = primary;
		LinkedHashSet<PermissionPair> all = new LinkedHashSet<>();
		if (primary != null) {
			all.add(primary);
		}
		all.addAll(Arrays.asList(implied));
		this.implied = Set.copyOf(all);
		this.staticRoleAllowlist = staticRoleAllowlist;
	}

	static PermissionPair p(DomainArea area, PermissionAction action) {
		return PermissionPair.of(area, action);
	}

	/** Enforced, configurable for Operator and Manager. */
	static CapabilitySpec om(PermissionPair primary, PermissionPair... implied) {
		return new CapabilitySpec(CapabilityAvailability.ENFORCED, null,
				EnumSet.of(ConfigurableRole.OPERATOR, ConfigurableRole.MANAGER), primary, implied, null);
	}

	/** Enforced, configurable for Operator, Manager and (narrow-only) Teacher. */
	static CapabilitySpec omt(PermissionPair primary, PermissionPair... implied) {
		return new CapabilitySpec(CapabilityAvailability.ENFORCED, null, EnumSet.allOf(ConfigurableRole.class),
				primary, implied, null);
	}

	/** System-managed: static roles keep {@code primary}; never configurable. */
	static CapabilitySpec locked(String reason, PermissionPair primary) {
		return new CapabilitySpec(CapabilityAvailability.LOCKED, reason, EnumSet.noneOf(ConfigurableRole.class),
				primary, new PermissionPair[0], null);
	}

	/** Like {@link #locked} but additionally restricted to the listed static roles. */
	static CapabilitySpec lockedFor(String reason, PermissionPair primary, String... roles) {
		return new CapabilitySpec(CapabilityAvailability.LOCKED, reason, EnumSet.noneOf(ConfigurableRole.class),
				primary, new PermissionPair[0], Set.of(roles));
	}

	/** No backing operation; nobody holds it. */
	static CapabilitySpec unavailable(String reason) {
		return new CapabilitySpec(CapabilityAvailability.UNAVAILABLE, reason, EnumSet.noneOf(ConfigurableRole.class),
				null, new PermissionPair[0], null);
	}

}
