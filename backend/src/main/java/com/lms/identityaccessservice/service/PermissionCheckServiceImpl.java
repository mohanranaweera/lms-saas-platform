package com.lms.identityaccessservice.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.CapabilityAvailability;
import com.lms.identityaccessservice.api.CapabilityGate;
import com.lms.identityaccessservice.api.ConfigurableRole;
import com.lms.identityaccessservice.api.PermissionPair;
import com.lms.identityaccessservice.repository.CapabilityGrantStore;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.domain.Role;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * RBAC-2's server-side permission enforcement mechanism. Evaluates the
 * current request's actor (read from {@link AuthenticatedPrincipalHolder},
 * never re-derived) against a static, code-level role -> permission matrix
 * transcribed from {@code docs/requirements/user-roles-and-permissions.md}
 * §2, per plan §7/§9.
 *
 * <p><b>Known tension, flagged not resolved (plan §17/§21):</b> the
 * transcribed matrix grants Finance Staff/Tenant Admin {@code DELETE} on
 * {@link DomainArea#FINANCE_EXPENSES} and {@code CREATE_EDIT} on
 * {@link DomainArea#PAYMENTS_SLIPS}. These grants must NEVER be used by a
 * future payment/ledger module to justify a literal row delete or a mutation
 * of a terminal-state payment - {@code .claude/rules/payments.md}'s
 * append-only rules govern those tables regardless of what this service
 * returns. This service only answers "does this role have this category of
 * capability" at the domain level; it is not itself a grant to bypass ledger
 * immutability, and the payments module's own endpoint-level checks must
 * enforce that separately.
 */
// Bean name explicitly pinned to "permissionCheckService" (rather than the
// default "permissionCheckServiceImpl" derived from the class name) because
// every @PreAuthorize SpEL expression in this codebase references the bean
// by that exact name (e.g. "@permissionCheckService.hasPermission(...)") -
// the default naming would silently break every one of those expressions at
// runtime (SpelEvaluationException, no such bean).
@Service("permissionCheckService")
public class PermissionCheckServiceImpl implements PermissionCheckService {

	private static final Map<Role, Map<DomainArea, Set<PermissionAction>>> MATRIX = buildMatrix();

	static {
		// Structural, startup-time guarantee (plan §15): Read-only Auditor must
		// never hold a write-class permission for any domain area. A passing
		// test suite alone only proves the *current* matrix has no mutation
		// grant for this role - it does not prevent a future developer from
		// silently adding one. This static initializer runs at class-load
		// time, so an accidental grant fails application startup, not just a
		// test run.
		Map<DomainArea, Set<PermissionAction>> auditorGrants = MATRIX.getOrDefault(Role.READ_ONLY_AUDITOR, Map.of());
		for (Map.Entry<DomainArea, Set<PermissionAction>> entry : auditorGrants.entrySet()) {
			if (!EnumSet.of(PermissionAction.VIEW).containsAll(entry.getValue())) {
				throw new IllegalStateException(
						"Read-only Auditor must never hold a write-class permission (CREATE_EDIT/DELETE/APPROVE), "
								+ "but the permission matrix grants " + entry.getValue() + " for " + entry.getKey());
			}
		}
	}

	/** Request-attribute prefix for the per-request memo of a role's tenant grants. */
	private static final String GRANTS_ATTRIBUTE = PermissionCheckServiceImpl.class.getName() + ".grants:";

	/** STAFF-MGMT-02: roles whose permissions are tenant-configured grants only. */
	private static final Set<Role> CONFIGURABLE_STAFF_ROLES = EnumSet.of(Role.OPERATOR, Role.MANAGER);

	/**
	 * STAFF-MGMT-03 §6.1: the seven legacy sub-roles promoted to configurable matrix columns.
	 * They keep their static matrix; a tenant override only changes annotated endpoints.
	 */
	private static final Set<Role> SEEDED_ROLES = EnumSet.of(Role.FINANCE_STAFF, Role.COURSE_COORDINATOR,
			Role.STUDENT_SUPPORT, Role.CONTENT_MANAGER, Role.EXAM_MANAGER, Role.ATTENDANCE_OPERATOR,
			Role.READ_ONLY_AUDITOR);

	/** {@code null} only for plain unit tests constructing this class directly (grants then deny). */
	private final CapabilityGrantStore grantStore;

	/** Static-matrix-only instance (unit tests): Operator/Manager/Teacher grants all read as denied. */
	public PermissionCheckServiceImpl() {
		this(null);
	}

	@Autowired
	public PermissionCheckServiceImpl(CapabilityGrantStore grantStore) {
		this.grantStore = grantStore;
	}

	@Override
	public boolean hasPermission(DomainArea domainArea, PermissionAction action) {
		String roleClaim = AuthenticatedPrincipalHolder.get().role();
		Role role;
		try {
			role = Role.valueOf(roleClaim);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			// Not a tenant-scope Role at all - either Platform Admin
			// (TokenService.PLATFORM_ADMIN_ROLE), an unrecognized value, or a
			// null role claim (e.g. an authenticated user with no role
			// assigned yet - AC15). Platform Admin's platform-scoped session
			// must never implicitly gain tenant-operational permissions
			// (plan AC11/§14), and a null/absent role must never fall
			// through to Enum.valueOf's NullPointerException (which would
			// otherwise surface as an unhandled 500) - deny in both cases.
			return false;
		}
		if (domainArea == null || action == null) {
			return false;
		}
		// Teacher/Teacher Assistant/Student are deliberately absent from the
		// matrix: their permission models are ownership/assignment-scoped,
		// not domain-flat V/C/E/D/A, and Teacher Assistant's boundary is
		// explicitly unratified (plan §2.3). Map.getOrDefault naturally
		// denies (empty set) for these roles without special-casing.
		if (CONFIGURABLE_STAFF_ROLES.contains(role)) {
			// STAFF-MGMT-02 §2.3: Operator/Manager hold no static grant. A pair is true only
			// while CapabilityEnforcementInterceptor has elevated it for THIS request (the
			// handler's @RequiresCapability was granted); every non-annotated endpoint fails closed.
			return isElevated(role, domainArea, action);
		}
		if (MATRIX.getOrDefault(role, Map.of()).getOrDefault(domainArea, Set.of()).contains(action)) {
			return true;
		}
		// STAFF-MGMT-03 §6.1: a seeded sub-role additionally holds the pairs elevated for THIS
		// request by an explicit tenant grant of the handler's capability (never off-request).
		return SEEDED_ROLES.contains(role) && isElevated(role, domainArea, action);
	}

	/**
	 * The interceptor's per-request elevation. Read-only Auditor never receives a write-class
	 * pair even if one were elevated (defence in depth behind the catalog's view-only rule, so
	 * the static "auditor never writes" invariant above holds at runtime too).
	 */
	private static boolean isElevated(Role role, DomainArea domainArea, PermissionAction action) {
		if (role == Role.READ_ONLY_AUDITOR && action != PermissionAction.VIEW) {
			return false;
		}
		return CapabilityElevationHolder.get().contains(PermissionPair.of(domainArea, action));
	}

	@Override
	public boolean hasCapability(Capability capability) {
		if (capability == null || !AuthenticatedPrincipalHolder.isSet()) {
			return false;
		}
		Role role = parseRole(AuthenticatedPrincipalHolder.get().role());
		if (role == null) {
			return false;
		}
		return switch (role) {
			case OPERATOR, MANAGER, TEACHER -> configuredGrant(capability, role, overridesFor(role));
			case TEACHER_ASSISTANT, STUDENT -> false;
			default -> SEEDED_ROLES.contains(role) ? seededGrant(capability, role, overridesFor(role))
					: staticCapability(capability, role);
		};
	}

	@Override
	public boolean hasCapability(String capabilityKey) {
		return Capability.fromKey(capabilityKey).map(this::hasCapability).orElse(false);
	}

	@Override
	public void requireCapability(Capability capability) {
		if (!hasCapability(capability)) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
	}

	@Override
	public Set<Capability> effectiveCapabilities() {
		Set<Capability> result = EnumSet.noneOf(Capability.class);
		for (Capability capability : Capability.values()) {
			if (hasCapability(capability)) {
				result.add(capability);
			}
		}
		return result;
	}

	@Override
	public CapabilityGate capabilityGate(Capability capability) {
		if (capability == null || !AuthenticatedPrincipalHolder.isSet()) {
			return CapabilityGate.PASS;
		}
		Role role = parseRole(AuthenticatedPrincipalHolder.get().role());
		if (role == null) {
			return CapabilityGate.PASS;
		}
		if (CONFIGURABLE_STAFF_ROLES.contains(role)) {
			return hasCapability(capability) ? CapabilityGate.ELEVATE : CapabilityGate.DENY;
		}
		if (role == Role.TEACHER) {
			return capability.isTeacherConfigurable() && !hasCapability(capability) ? CapabilityGate.DENY
					: CapabilityGate.PASS;
		}
		if (SEEDED_ROLES.contains(role)) {
			ConfigurableRole configurableRole = ConfigurableRole.valueOf(role.name());
			Map<String, Boolean> overrides = overridesFor(role);
			Boolean explicit = (overrides == null || !capability.isConfigurableFor(configurableRole)) ? null
					: overrides.get(capability.key());
			if (explicit == null) {
				return CapabilityGate.PASS;
			}
			if (!explicit) {
				return CapabilityGate.DENY;
			}
			// An explicit grant only elevates when it adds something the role does not hold by
			// default; a grant equal to the default must never widen access (STAFF-MGMT-03 QA).
			return defaultGrant(configurableRole, capability) ? CapabilityGate.PASS : CapabilityGate.ELEVATE;
		}
		return CapabilityGate.PASS;
	}

	@Override
	public boolean isDelegated(DomainArea domainArea, PermissionAction action) {
		if (!AuthenticatedPrincipalHolder.isSet() || domainArea == null || action == null) {
			return false;
		}
		Role role = parseRole(AuthenticatedPrincipalHolder.get().role());
		if (role == null) {
			return false;
		}
		if (CONFIGURABLE_STAFF_ROLES.contains(role)) {
			return true;
		}
		return SEEDED_ROLES.contains(role) && !staticMatrixGrants(role, PermissionPair.of(domainArea, action))
				&& isElevated(role, domainArea, action);
	}

	@Override
	public boolean roleHoldsCapability(String roleCode, Capability capability) {
		Role role = parseRole(roleCode);
		if (role == null || capability == null) {
			return false;
		}
		return switch (role) {
			case OPERATOR, MANAGER, TEACHER -> configuredGrant(capability, role, currentTenantOverrides(role));
			case TEACHER_ASSISTANT, STUDENT -> false;
			default -> SEEDED_ROLES.contains(role) ? seededGrant(capability, role, currentTenantOverrides(role))
					: staticCapability(capability, role);
		};
	}

	/**
	 * Default grant of {@code role} for {@code capability} when the tenant has no override row
	 * (also used by the permission matrix view): seeded sub-roles - the static matrix holds the
	 * capability's primary gate (plus its static allowlist); Teacher - granted unless the
	 * capability opts out ({@link Capability#teacherDefaultGranted()}); Operator/Manager - not
	 * granted.
	 */
	public static boolean defaultGrant(ConfigurableRole role, Capability capability) {
		if (role.isSeeded()) {
			return staticCapability(capability, Role.valueOf(role.name()));
		}
		if (role == ConfigurableRole.TEACHER) {
			// TUTE-01: a capability may opt out of the Teacher default (true for every older one).
			return role.defaultGranted() && capability.teacherDefaultGranted();
		}
		return role.defaultGranted();
	}

	/** Tenant Admin / legacy sub-roles: the capability's primary static gate (+ allowlist). */
	private static boolean staticCapability(Capability capability, Role role) {
		if (capability.availability() == CapabilityAvailability.UNAVAILABLE || capability.primary() == null) {
			return false;
		}
		Set<String> allowlist = capability.staticRoleAllowlist();
		if (allowlist != null && !allowlist.contains(role.name())) {
			return false;
		}
		return staticMatrixGrants(role, capability.primary());
	}

	/** Operator/Manager/Teacher: ENFORCED + configurable for the role + the tenant's grant (or default). */
	private static boolean configuredGrant(Capability capability, Role role, Map<String, Boolean> overrides) {
		if (role == Role.TEACHER && !capability.isTeacherConfigurable()) {
			return false;
		}
		ConfigurableRole configurableRole = ConfigurableRole.valueOf(role.name());
		if (!capability.isConfigurableFor(configurableRole) || overrides == null) {
			return false;
		}
		return overrides.getOrDefault(capability.key(), defaultGrant(configurableRole, capability));
	}

	/**
	 * Seeded sub-role (STAFF-MGMT-03 §6.1): an explicit tenant row (grant or revoke) wins for a
	 * capability configurable for the role; otherwise - including when no grant store / tenant is
	 * available - the static default, i.e. exactly the pre-STAFF-MGMT-03 behaviour.
	 */
	private static boolean seededGrant(Capability capability, Role role, Map<String, Boolean> overrides) {
		ConfigurableRole configurableRole = ConfigurableRole.valueOf(role.name());
		if (overrides != null && capability.isConfigurableFor(configurableRole)) {
			Boolean explicit = overrides.get(capability.key());
			if (explicit != null) {
				return explicit;
			}
		}
		return staticCapability(capability, role);
	}

	/**
	 * The current tenant's explicit grant rows for {@code role}, read from the database once
	 * per HTTP request (memoised as a request attribute, so it never outlives the request nor
	 * is seen by another one; no memo off-request). {@code null} = fail closed: no grant store,
	 * no tenant context, or a principal whose tenant differs from the resolved tenant.
	 */
	private Map<String, Boolean> overridesFor(Role role) {
		if (grantStore == null || !TenantContextHolder.isSet()) {
			return null;
		}
		UUID tenantId = new TenantContextHolder().getTenantId();
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (principal.tenantId() == null || !principal.tenantId().equals(tenantId)) {
			return null;
		}
		String key = GRANTS_ATTRIBUTE + tenantId + ":" + principal.userId() + ":" + role.name();
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes != null) {
			Object cached = attributes.getAttribute(key, RequestAttributes.SCOPE_REQUEST);
			if (cached instanceof Map<?, ?> map) {
				@SuppressWarnings("unchecked")
				Map<String, Boolean> typed = (Map<String, Boolean>) map;
				return typed;
			}
		}
		Map<String, Boolean> overrides = Map.copyOf(grantStore.findOverridesForRole(role.name()));
		if (attributes != null) {
			attributes.setAttribute(key, overrides, RequestAttributes.SCOPE_REQUEST);
		}
		return overrides;
	}

	/** Like {@link #overridesFor} but principal-independent and never memoised. */
	private Map<String, Boolean> currentTenantOverrides(Role role) {
		if (grantStore == null || !TenantContextHolder.isSet()) {
			return null;
		}
		return Map.copyOf(grantStore.findOverridesForRole(role.name()));
	}

	/** Does {@code role}'s static matrix hold {@code pair}? (Also used by the catalog invariant test.) */
	public static boolean staticMatrixGrants(Role role, PermissionPair pair) {
		return MATRIX.getOrDefault(role, Map.of()).getOrDefault(pair.area(), Set.of()).contains(pair.action());
	}

	private static Role parseRole(String roleClaim) {
		try {
			return Role.valueOf(roleClaim);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			return null;
		}
	}

	@Override
	public boolean hasPermission(String domainArea, String action) {
		DomainArea parsedDomainArea;
		PermissionAction parsedAction;
		try {
			parsedDomainArea = DomainArea.valueOf(domainArea);
			parsedAction = PermissionAction.valueOf(action);
		}
		catch (IllegalArgumentException | NullPointerException ex) {
			// A null domainArea/action (in addition to an unrecognized
			// string) must deny rather than throw - Enum.valueOf(Class, null)
			// throws NullPointerException, not IllegalArgumentException.
			return false;
		}
		return hasPermission(parsedDomainArea, parsedAction);
	}

	@Override
	public void requirePermission(DomainArea domainArea, PermissionAction action) {
		if (!hasPermission(domainArea, action)) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
	}

	/**
	 * Transcribed by hand from {@code docs/requirements/user-roles-and-permissions.md}
	 * §2's table, cell by cell, including every blank ({@code —}) cell as "no
	 * entry" (absence = deny). Only the 8 staff-sub-role columns are
	 * represented - see class javadoc for why Teacher/Teacher
	 * Assistant/Student are excluded.
	 *
	 * <p>Some source cells carry a qualifier this flat model cannot represent
	 * (e.g. Devices' "V (request only)" for Student Support, Access & expiry's
	 * "V (approve if finance-adjacent)" for Finance Staff, Audit log's
	 * "V (own-area actions)" for every non-auditor role): the base action
	 * letter is transcribed as granted, the parenthetical narrowing is NOT
	 * separately encoded here (this mechanism only answers domain-level
	 * category, not field/scope-level nuance) - flagged inline per row.
	 */
	private static Map<Role, Map<DomainArea, Set<PermissionAction>>> buildMatrix() {
		Map<Role, Map<DomainArea, Set<PermissionAction>>> matrix = new EnumMap<>(Role.class);

		matrix.put(Role.TENANT_ADMIN, roleGrants(builder -> builder
			.grant(DomainArea.STUDENTS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.DELETE)
			.grant(DomainArea.TEACHERS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.DELETE)
			.grant(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
					PermissionAction.DELETE)
			.grant(DomainArea.COURSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.DELETE)
			.grant(DomainArea.MATERIALS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.DELETE)
			.grant(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
					PermissionAction.APPROVE)
			.grant(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
					PermissionAction.DELETE)
			.grant(DomainArea.ATTENDANCE, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
			.grant(DomainArea.EXAMS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.APPROVE)
			.grant(DomainArea.DEVICES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
			.grant(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
					PermissionAction.APPROVE)
			.grant(DomainArea.REVIEWS_MODERATION, PermissionAction.VIEW, PermissionAction.APPROVE)
			.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)
			.grant(DomainArea.BRANDING_SETTINGS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
			.grant(DomainArea.SUPPORT_TICKETS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
					PermissionAction.DELETE)
			// Wave 4 (PAR-19-03) - full grant, mirrors this role's pattern
			// elsewhere in the matrix.
			.grant(DomainArea.LIVE_CLASSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
					PermissionAction.DELETE, PermissionAction.APPROVE)
			// PAY-GW-01 / ADR-017 - only the Tenant Admin may configure the
			// tenant's own merchant account.
			.grant(DomainArea.PAYMENT_GATEWAY, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
			// MEDIA-INT-01 / ADR-018 - only the Tenant Admin configures the
			// tenant's media/meeting provider integrations.
			.grant(DomainArea.INTEGRATIONS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
			// TEN-DOM-01 / ADR-021 - only the Tenant Admin manages custom domains.
			.grant(DomainArea.CUSTOM_DOMAINS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
			// Wave 11 (decision D2) - broadcasts, templates, delivery logs.
			.grant(DomainArea.COMMUNICATIONS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
			// DISC-01 (owner decision 2026-10-21) - only the Tenant Admin
			// creates / edits / toggles / archives discount rules.
			.grant(DomainArea.DISCOUNTS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
			// ADR-030 (F1) - only the Tenant Admin issues / revokes Free Cards.
			.grant(DomainArea.FREE_CARDS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
					PermissionAction.DELETE)
			// ADR-033 - only the Tenant Admin may start a "Log as Student" support session.
			.grant(DomainArea.STUDENT_IMPERSONATION, PermissionAction.CREATE_EDIT)
			// TUTE-01 section 8 - Tenant Admin only; every other role needs an explicit
			// per-capability grant (Teacher: own courses only).
			.grant(DomainArea.TUTES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.DELETE,
					PermissionAction.APPROVE)));

		matrix.put(Role.FINANCE_STAFF, roleGrants(builder -> builder.grant(DomainArea.STUDENTS, PermissionAction.VIEW)
			.grant(DomainArea.COURSES, PermissionAction.VIEW)
			.grant(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
					PermissionAction.APPROVE)
			.grant(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
					PermissionAction.DELETE)
			// "V (approve if finance-adjacent)" - base V transcribed, the
			// conditional APPROVE nuance is not encoded at this domain-level
			// granularity.
			.grant(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW)
			// "V (own-area actions)"
			.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)
			// PAY-GW-01 - status only (enabled/mode), never the secret.
			.grant(DomainArea.PAYMENT_GATEWAY, PermissionAction.VIEW)
			// DISC-01 - view rules (codes masked), never edit them.
			.grant(DomainArea.DISCOUNTS, PermissionAction.VIEW)
			// ADR-030 (F1) - view Free Cards; recording payments never implies issuing them.
			.grant(DomainArea.FREE_CARDS, PermissionAction.VIEW)));

		matrix.put(Role.COURSE_COORDINATOR,
				roleGrants(builder -> builder.grant(DomainArea.STUDENTS, PermissionAction.VIEW)
					.grant(DomainArea.TEACHERS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
					.grant(DomainArea.COURSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
							PermissionAction.APPROVE)
					.grant(DomainArea.MATERIALS, PermissionAction.VIEW)
					.grant(DomainArea.REVIEWS_MODERATION, PermissionAction.VIEW, PermissionAction.APPROVE)
					.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)
					// Wave 4 (PAR-19-03) - "Course Coordinator V/C/E/A" per the
					// parity matrix's own suggested mapping (Wave 4 plan §3/§10
					// judgment call 3) - no DELETE.
					.grant(DomainArea.LIVE_CLASSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
							PermissionAction.APPROVE)));

		matrix.put(Role.STUDENT_SUPPORT,
				roleGrants(builder -> builder
					.grant(DomainArea.STUDENTS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
					.grant(DomainArea.TEACHERS, PermissionAction.VIEW)
					.grant(DomainArea.COURSES, PermissionAction.VIEW)
					.grant(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW)
					// "V (request only)" - base V transcribed, the narrower
					// "request only" scope is not encoded at this domain-level
					// granularity.
					.grant(DomainArea.DEVICES, PermissionAction.VIEW)
					.grant(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW)
					.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)
					.grant(DomainArea.SUPPORT_TICKETS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
					// Wave 11 (decision D2) - Student Support sends broadcasts
					// and manages broadcast templates.
					.grant(DomainArea.COMMUNICATIONS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
					// DISC-01 - view rules (codes masked), never edit them.
					.grant(DomainArea.DISCOUNTS, PermissionAction.VIEW)
					// ADR-030 (F1) - view Free Cards only.
					.grant(DomainArea.FREE_CARDS, PermissionAction.VIEW)));

		matrix.put(Role.CONTENT_MANAGER,
				roleGrants(builder -> builder.grant(DomainArea.STUDENTS, PermissionAction.VIEW)
					.grant(DomainArea.COURSES, PermissionAction.VIEW)
					.grant(DomainArea.MATERIALS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
							PermissionAction.DELETE)
					.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)));

		matrix.put(Role.EXAM_MANAGER, roleGrants(builder -> builder.grant(DomainArea.STUDENTS, PermissionAction.VIEW)
			.grant(DomainArea.COURSES, PermissionAction.VIEW)
			.grant(DomainArea.EXAMS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.APPROVE)
			.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)));

		matrix.put(Role.ATTENDANCE_OPERATOR,
				roleGrants(builder -> builder.grant(DomainArea.STUDENTS, PermissionAction.VIEW)
					.grant(DomainArea.COURSES, PermissionAction.VIEW)
					.grant(DomainArea.ATTENDANCE, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
					.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)));

		matrix.put(Role.READ_ONLY_AUDITOR,
				roleGrants(builder -> builder.grant(DomainArea.STUDENTS, PermissionAction.VIEW)
					.grant(DomainArea.TEACHERS, PermissionAction.VIEW)
					.grant(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW)
					.grant(DomainArea.COURSES, PermissionAction.VIEW)
					.grant(DomainArea.MATERIALS, PermissionAction.VIEW)
					.grant(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW)
					.grant(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW)
					.grant(DomainArea.ATTENDANCE, PermissionAction.VIEW)
					.grant(DomainArea.EXAMS, PermissionAction.VIEW)
					.grant(DomainArea.DEVICES, PermissionAction.VIEW)
					.grant(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW)
					.grant(DomainArea.REVIEWS_MODERATION, PermissionAction.VIEW)
					// "V (full)"
					.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)
					.grant(DomainArea.BRANDING_SETTINGS, PermissionAction.VIEW)
					.grant(DomainArea.SUPPORT_TICKETS, PermissionAction.VIEW)
					.grant(DomainArea.PAYMENT_GATEWAY, PermissionAction.VIEW)
					.grant(DomainArea.INTEGRATIONS, PermissionAction.VIEW)
					.grant(DomainArea.CUSTOM_DOMAINS, PermissionAction.VIEW)
					.grant(DomainArea.COMMUNICATIONS, PermissionAction.VIEW)
					.grant(DomainArea.DISCOUNTS, PermissionAction.VIEW)
					.grant(DomainArea.FREE_CARDS, PermissionAction.VIEW)));

		return Map.copyOf(matrix);
	}

	private static Map<DomainArea, Set<PermissionAction>> roleGrants(Consumer<RoleGrantBuilder> configurer) {
		RoleGrantBuilder builder = new RoleGrantBuilder();
		configurer.accept(builder);
		return builder.build();
	}

	/** Small internal helper so {@link #buildMatrix()} reads as a flat transcription of the matrix. */
	private static final class RoleGrantBuilder {

		private final Map<DomainArea, Set<PermissionAction>> grants = new EnumMap<>(DomainArea.class);

		RoleGrantBuilder grant(DomainArea domainArea, PermissionAction... actions) {
			grants.put(domainArea, EnumSet.copyOf(Arrays.asList(actions)));
			return this;
		}

		Map<DomainArea, Set<PermissionAction>> build() {
			return Map.copyOf(grants);
		}

	}

}
