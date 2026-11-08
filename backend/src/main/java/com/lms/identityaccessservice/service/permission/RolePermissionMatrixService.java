package com.lms.identityaccessservice.service.permission;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.CapabilityAvailability;
import com.lms.identityaccessservice.api.CapabilityGroup;
import com.lms.identityaccessservice.api.ConfigurableRole;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.error.InvalidPermissionChangeException;
import com.lms.identityaccessservice.error.PermissionsStaleException;
import com.lms.identityaccessservice.repository.CapabilityGrantStore;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * STAFF-MGMT-02 §2.4-§2.7: reads and atomically updates a tenant's configurable role
 * permissions. The whole batch is validated before anything is written (no partial write);
 * the version counter is advanced optimistically ({@code 409 PERMISSIONS_STALE} when another
 * editor saved first); the audit entry is written in the same transaction, so a rolled-back
 * save leaves no entry. Tenant identity always comes from the trusted {@link TenantContext}.
 */
@Service
public class RolePermissionMatrixService {

	static final String AUDIT_ACTION = "staff.role_permissions_changed";

	static final String AUDIT_TARGET = "tenant_role_permission_version";

	static final String TEACHER_OWNERSHIP_REASON = "Teacher access is ownership-scoped";

	static final String NOT_CONFIGURABLE_REASON = "Not configurable for this role";

	static final int MAX_CHANGES = 1000;

	private final CapabilityGrantStore grantStore;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public RolePermissionMatrixService(CapabilityGrantStore grantStore, PermissionCheckService permissionCheckService,
			AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.grantStore = grantStore;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public PermissionMatrixView read() {
		permissionCheckService.requireCapability(Capability.STAFF_VIEW);
		return build(grantStore.currentVersion(), grantStore.findAllOverrides());
	}

	@Transactional
	public PermissionMatrixView update(Long expectedVersion, List<PermissionChange> changes) {
		permissionCheckService.requireCapability(Capability.STAFF_PERMISSIONS_EDIT);
		AuthenticatedPrincipal actor = AuthenticatedPrincipalHolder.get();
		if (expectedVersion == null || expectedVersion < 0) {
			throw new InvalidPermissionChangeException("expectedVersion is required");
		}
		if (changes == null) {
			throw new InvalidPermissionChangeException("changes are required");
		}
		if (changes.size() > MAX_CHANGES) {
			throw new InvalidPermissionChangeException("Too many changes in one save");
		}

		// 1. Validate the whole batch before touching anything.
		List<Validated> validated = new ArrayList<>(changes.size());
		Set<String> seen = new HashSet<>();
		for (PermissionChange change : changes) {
			Validated v = validate(change, actor);
			if (!seen.add(v.role().name() + "/" + v.capability().key())) {
				throw new InvalidPermissionChangeException(
						"Duplicate change for " + v.role().name() + " / " + v.capability().key());
			}
			validated.add(v);
		}

		// 2. Drop no-op changes (already the effective value).
		Map<String, Map<String, Boolean>> overrides = grantStore.findAllOverrides();
		List<Validated> effective = validated.stream()
			.filter(v -> v.granted() != effectiveGrant(overrides, v.role(), v.capability()))
			.toList();

		// 3. Optimistic version check (also for an all-no-op save, so a stale editor learns it).
		if (effective.isEmpty()) {
			long current = grantStore.currentVersion();
			if (current != expectedVersion) {
				throw new PermissionsStaleException();
			}
			return build(current, overrides);
		}
		if (!grantStore.advanceVersion(expectedVersion, actor.userId())) {
			throw new PermissionsStaleException();
		}

		// 4. Apply + audit in this same transaction.
		List<Map<String, Object>> granted = new ArrayList<>();
		List<Map<String, Object>> revoked = new ArrayList<>();
		for (Validated v : effective) {
			grantStore.upsert(v.role().name(), v.capability().key(), v.granted(), actor.userId());
			Map<String, Object> entry = Map.of("role", v.role().name(), "capability", v.capability().key());
			(v.granted() ? granted : revoked).add(entry);
		}
		long toVersion = expectedVersion + 1;
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("fromVersion", expectedVersion);
		metadata.put("toVersion", toVersion);
		metadata.put("granted", granted);
		metadata.put("revoked", revoked);
		auditLogApi.record(new AuditLogEntry(actor.userId(), AUDIT_ACTION, AUDIT_TARGET, tenantContext.getTenantId(),
				null, metadata));
		return build(toVersion, grantStore.findAllOverrides());
	}

	private record Validated(ConfigurableRole role, Capability capability, boolean granted) {

	}

	private Validated validate(PermissionChange change, AuthenticatedPrincipal actor) {
		if (change == null || change.granted() == null) {
			throw new InvalidPermissionChangeException("Each change needs roleCode, capabilityKey and granted");
		}
		ConfigurableRole role = ConfigurableRole.parse(change.roleCode())
			.orElseThrow(() -> new InvalidPermissionChangeException("Unknown or non-configurable role"));
		Capability capability = Capability.fromKey(change.capabilityKey())
			.orElseThrow(() -> new InvalidPermissionChangeException("Unknown capability"));
		if (capability.availability() != CapabilityAvailability.ENFORCED) {
			throw new InvalidPermissionChangeException(
					capability.key() + " is " + capability.availability().name().toLowerCase() + " and cannot be changed");
		}
		if (!capability.isConfigurableFor(role)) {
			throw new InvalidPermissionChangeException(capability.key() + " is not configurable for " + role.name());
		}
		if (role.name().equals(actor.role())) {
			throw new AccessDeniedException("You cannot change the permissions of your own role");
		}
		if (change.granted() && !permissionCheckService.hasCapability(capability)) {
			throw new AccessDeniedException("You cannot grant a permission you do not hold");
		}
		return new Validated(role, capability, change.granted());
	}

	private static boolean effectiveGrant(Map<String, Map<String, Boolean>> overrides, ConfigurableRole role,
			Capability capability) {
		return Optional.ofNullable(overrides.get(role.name()))
			.map(m -> m.get(capability.key()))
			.orElse(role.defaultGranted());
	}

	private static PermissionMatrixView build(long version, Map<String, Map<String, Boolean>> overrides) {
		List<PermissionMatrixView.RoleColumn> roles = new ArrayList<>();
		for (ConfigurableRole role : ConfigurableRole.values()) {
			roles.add(new PermissionMatrixView.RoleColumn(role.name(), role.displayName()));
		}
		Map<CapabilityGroup, List<PermissionMatrixView.Row>> rowsByGroup = new EnumMap<>(CapabilityGroup.class);
		for (Capability capability : Capability.values()) {
			Map<String, PermissionMatrixView.Cell> cells = new LinkedHashMap<>();
			for (ConfigurableRole role : ConfigurableRole.values()) {
				cells.put(role.name(), cell(overrides, role, capability));
			}
			rowsByGroup.computeIfAbsent(capability.group(), g -> new ArrayList<>())
				.add(new PermissionMatrixView.Row(capability.key(), capability.label(),
						capability.availability().name(), capability.reason(), cells));
		}
		List<PermissionMatrixView.Group> groups = new ArrayList<>();
		rowsByGroup.forEach((group, rows) -> groups
			.add(new PermissionMatrixView.Group(group.name(), group.label(), List.copyOf(rows))));
		return new PermissionMatrixView(version, List.copyOf(roles), List.copyOf(groups));
	}

	private static PermissionMatrixView.Cell cell(Map<String, Map<String, Boolean>> overrides, ConfigurableRole role,
			Capability capability) {
		if (capability.availability() == CapabilityAvailability.UNAVAILABLE) {
			return new PermissionMatrixView.Cell("UNAVAILABLE", capability.reason());
		}
		if (capability.availability() == CapabilityAvailability.LOCKED) {
			return new PermissionMatrixView.Cell("LOCKED", capability.reason());
		}
		if (!capability.isConfigurableFor(role)) {
			return new PermissionMatrixView.Cell("LOCKED",
					role == ConfigurableRole.TEACHER ? TEACHER_OWNERSHIP_REASON : NOT_CONFIGURABLE_REASON);
		}
		return new PermissionMatrixView.Cell(effectiveGrant(overrides, role, capability) ? "GRANTED" : "NOT_GRANTED",
				null);
	}

	/** Current tenant's matrix version (0 = never saved). */
	@Transactional(readOnly = true)
	public long currentVersion() {
		return grantStore.currentVersion();
	}

}
