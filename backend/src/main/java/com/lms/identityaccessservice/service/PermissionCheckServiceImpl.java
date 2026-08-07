package com.lms.identityaccessservice.service;

import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.domain.Role;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

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

	@Override
	public boolean hasPermission(DomainArea domainArea, PermissionAction action) {
		String roleClaim = AuthenticatedPrincipalHolder.get().role();
		Role role;
		try {
			role = Role.valueOf(roleClaim);
		}
		catch (IllegalArgumentException ex) {
			// Not a tenant-scope Role at all - either Platform Admin
			// (TokenService.PLATFORM_ADMIN_ROLE) or an unrecognized value.
			// Platform Admin's platform-scoped session must never implicitly
			// gain tenant-operational permissions (plan AC11/§14) - deny.
			return false;
		}
		// Teacher/Teacher Assistant/Student are deliberately absent from the
		// matrix: their permission models are ownership/assignment-scoped,
		// not domain-flat V/C/E/D/A, and Teacher Assistant's boundary is
		// explicitly unratified (plan §2.3). Map.getOrDefault naturally
		// denies (empty set) for these roles without special-casing.
		Set<PermissionAction> grants = MATRIX.getOrDefault(role, Map.of()).getOrDefault(domainArea, Set.of());
		return grants.contains(action);
	}

	@Override
	public boolean hasPermission(String domainArea, String action) {
		DomainArea parsedDomainArea;
		PermissionAction parsedAction;
		try {
			parsedDomainArea = DomainArea.valueOf(domainArea);
			parsedAction = PermissionAction.valueOf(action);
		}
		catch (IllegalArgumentException ex) {
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
					PermissionAction.DELETE)));

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
			.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)));

		matrix.put(Role.COURSE_COORDINATOR,
				roleGrants(builder -> builder.grant(DomainArea.STUDENTS, PermissionAction.VIEW)
					.grant(DomainArea.TEACHERS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)
					.grant(DomainArea.COURSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
							PermissionAction.APPROVE)
					.grant(DomainArea.MATERIALS, PermissionAction.VIEW)
					.grant(DomainArea.REVIEWS_MODERATION, PermissionAction.VIEW, PermissionAction.APPROVE)
					.grant(DomainArea.AUDIT_LOG, PermissionAction.VIEW)));

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
					.grant(DomainArea.SUPPORT_TICKETS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT)));

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
					.grant(DomainArea.SUPPORT_TICKETS, PermissionAction.VIEW)));

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
