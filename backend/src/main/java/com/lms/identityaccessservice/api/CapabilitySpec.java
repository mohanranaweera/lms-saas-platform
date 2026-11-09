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

	/**
	 * Teacher's default when the tenant has no explicit row: {@code true} (the historical
	 * per-role default - Teacher capabilities only narrow) except for {@link #omtTeacherOff}
	 * capabilities, which a Teacher holds only after an explicit tenant grant.
	 */
	final boolean teacherDefaultGranted;

	private CapabilitySpec(CapabilityAvailability availability, String reason, Set<ConfigurableRole> configurableFor,
			PermissionPair primary, PermissionPair[] implied, Set<String> staticRoleAllowlist) {
		this(availability, reason, configurableFor, primary, implied, staticRoleAllowlist, true);
	}

	private CapabilitySpec(CapabilityAvailability availability, String reason, Set<ConfigurableRole> configurableFor,
			PermissionPair primary, PermissionPair[] implied, Set<String> staticRoleAllowlist,
			boolean teacherDefaultGranted) {
		this.teacherDefaultGranted = teacherDefaultGranted;
		this.availability = availability;
		this.reason = reason;
		this.primary = primary;
		LinkedHashSet<PermissionPair> all = new LinkedHashSet<>();
		if (primary != null) {
			all.add(primary);
		}
		all.addAll(Arrays.asList(implied));
		this.implied = Set.copyOf(all);
		this.configurableFor = withSeededRoles(configurableFor, this.implied);
		this.staticRoleAllowlist = staticRoleAllowlist;
	}

	/**
	 * STAFF-MGMT-03 §6.1: every capability configurable for Operator/Manager is also configurable
	 * for the seven seeded sub-roles - except Read-only Auditor, which is configurable only when
	 * every pair the capability elevates is a VIEW (so an explicit grant can never hand the
	 * auditor a write; the static matrix invariant in PermissionCheckServiceImpl is untouched).
	 */
	private static Set<ConfigurableRole> withSeededRoles(Set<ConfigurableRole> base, Set<PermissionPair> implied) {
		if (base.isEmpty()) {
			return Set.of();
		}
		EnumSet<ConfigurableRole> roles = EnumSet.copyOf(base);
		if (base.contains(ConfigurableRole.OPERATOR)) {
			boolean viewOnly = !implied.isEmpty()
					&& implied.stream().allMatch(pair -> pair.action() == PermissionAction.VIEW);
			for (ConfigurableRole role : ConfigurableRole.values()) {
				if (role.isSeeded() && (role != ConfigurableRole.READ_ONLY_AUDITOR || viewOnly)) {
					roles.add(role);
				}
			}
		}
		return Set.copyOf(roles);
	}

	static PermissionPair p(DomainArea area, PermissionAction action) {
		return PermissionPair.of(area, action);
	}

	/** Enforced, configurable for Operator, Manager and the seeded roles (see {@link #withSeededRoles}). */
	static CapabilitySpec om(PermissionPair primary, PermissionPair... implied) {
		return new CapabilitySpec(CapabilityAvailability.ENFORCED, null,
				EnumSet.of(ConfigurableRole.OPERATOR, ConfigurableRole.MANAGER), primary, implied, null);
	}

	/**
	 * Enforced and configurable like {@link #om}, but a static role holds it by default only when
	 * it is in {@code roles} (as well as holding {@code primary}): used where the endpoint's code
	 * path checks more than the primary gate, so the default shown matches what actually works.
	 */
	static CapabilitySpec omFor(String[] roles, PermissionPair primary, PermissionPair... implied) {
		return new CapabilitySpec(CapabilityAvailability.ENFORCED, null,
				EnumSet.of(ConfigurableRole.OPERATOR, ConfigurableRole.MANAGER), primary, implied, Set.of(roles));
	}

	/** Enforced, configurable for Operator, Manager, the seeded roles and (narrow-only) Teacher. */
	static CapabilitySpec omt(PermissionPair primary, PermissionPair... implied) {
		return new CapabilitySpec(CapabilityAvailability.ENFORCED, null,
				EnumSet.of(ConfigurableRole.OPERATOR, ConfigurableRole.MANAGER, ConfigurableRole.TEACHER), primary,
				implied, null);
	}

	/**
	 * Like {@link #omt} (configurable for Operator, Manager, the seeded roles and Teacher), but a
	 * Teacher does NOT hold it by default: a new feature never silently broadens Teacher access
	 * (TUTE-01 section 8). Once granted, the Teacher's access stays ownership-scoped by the
	 * owning module; the grant never elevates a static gate.
	 */
	static CapabilitySpec omtTeacherOff(PermissionPair primary, PermissionPair... implied) {
		return new CapabilitySpec(CapabilityAvailability.ENFORCED, null,
				EnumSet.of(ConfigurableRole.OPERATOR, ConfigurableRole.MANAGER, ConfigurableRole.TEACHER), primary,
				implied, null, false);
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
