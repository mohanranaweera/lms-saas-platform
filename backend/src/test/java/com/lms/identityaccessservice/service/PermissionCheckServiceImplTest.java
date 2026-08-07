package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.domain.Role;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.access.AccessDeniedException;

/**
 * Plain-JUnit unit tests (no Spring context - {@link AuthenticatedPrincipalHolder}
 * is a bare thread-local, so {@code set(...)}/{@code clear()} around each case
 * is enough) for RBAC-2's permission matrix in {@link PermissionCheckServiceImpl}.
 *
 * <p>{@link #DOC_MATRIX} below is transcribed independently from
 * {@code docs/requirements/user-roles-and-permissions.md} §2's table - read
 * directly from that document, cell by cell, NOT copied from {@link
 * PermissionCheckServiceImpl}'s own {@code buildMatrix()} - so a wrong
 * transcription in the implementation would be caught by this test rather
 * than tautologically confirmed by re-deriving the same mistake.
 */
class PermissionCheckServiceImplTest {

	private final PermissionCheckServiceImpl service = new PermissionCheckServiceImpl();

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@ParameterizedTest(name = "{0} / {1} / {2} -> {3}")
	@MethodSource("matrixCases")
	void matrixCellMatchesDocSection2(Role role, DomainArea domainArea, PermissionAction action, boolean expected) {
		AuthenticatedPrincipalHolder.set(principalFor(role));
		try {
			assertThat(service.hasPermission(domainArea, action)).isEqualTo(expected);
		}
		finally {
			AuthenticatedPrincipalHolder.clear();
		}
	}

	@Test
	void readOnlyAuditorNeverHasAnyMutatingPermission() {
		// Full sweep across every DomainArea x every mutating PermissionAction -
		// not just the documented cells - since this is the single hardest
		// guarantee in the whole module (docs/requirements/user-roles-and-permissions.md
		// §2's closing note: "no mutating endpoint may succeed for this role,
		// regardless of what a stale client UI exposes").
		AuthenticatedPrincipalHolder.set(principalFor(Role.READ_ONLY_AUDITOR));
		try {
			for (DomainArea domainArea : DomainArea.values()) {
				for (PermissionAction action : EnumSet.of(PermissionAction.CREATE_EDIT, PermissionAction.DELETE,
						PermissionAction.APPROVE)) {
					assertThat(service.hasPermission(domainArea, action))
						.as("Read-only Auditor must never have %s on %s", action, domainArea)
						.isFalse();
				}
			}
		}
		finally {
			AuthenticatedPrincipalHolder.clear();
		}
	}

	@Test
	void platformAdminNeverImplicitlyHasTenantPermission() {
		// Principal shape a real Platform Admin request actually resolves to
		// (JwtAuthenticationFilter#resolvePlatformAdminPrincipal): role claim is
		// TokenService.PLATFORM_ADMIN_ROLE (read from that constant, not assumed
		// to be the literal string), tenantId is null (no tenant exists for that
		// actor).
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), null, TokenService.PLATFORM_ADMIN_ROLE, UUID.randomUUID()));
		try {
			// Representative spread: at least one VIEW and one mutating case per a
			// few areas, including areas a Tenant Admin would normally control.
			assertThat(service.hasPermission(DomainArea.STUDENTS, PermissionAction.VIEW)).isFalse();
			assertThat(service.hasPermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT)).isFalse();
			assertThat(service.hasPermission(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW)).isFalse();
			assertThat(service.hasPermission(DomainArea.STAFF_AND_ROLES, PermissionAction.CREATE_EDIT)).isFalse();
			assertThat(service.hasPermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW)).isFalse();
			assertThat(service.hasPermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE)).isFalse();
			assertThat(service.hasPermission(DomainArea.FINANCE_EXPENSES, PermissionAction.DELETE)).isFalse();
			assertThat(service.hasPermission(DomainArea.AUDIT_LOG, PermissionAction.VIEW)).isFalse();
		}
		finally {
			AuthenticatedPrincipalHolder.clear();
		}
	}

	@Test
	void teacherTeacherAssistantStudentAlwaysDenied() {
		// Teacher / Teacher Assistant / Student are deliberately absent from this
		// domain-flat V/C/E/D/A matrix: their permission models are
		// ownership/assignment-scoped (e.g. "only this teacher's assigned
		// courses"), not a domain-wide category grant like the 8 staff
		// sub-roles above, and that ownership-scoped model is not built by this
		// module (Teacher Assistant's boundary is additionally explicitly
		// unratified/PROVISIONAL - doc §3). This is a full sweep (every
		// DomainArea x every PermissionAction) proving the matrix denies-by
		// -default for all three roles, rather than silently falling back to
		// some other role's grants.
		for (Role role : EnumSet.of(Role.TEACHER, Role.TEACHER_ASSISTANT, Role.STUDENT)) {
			AuthenticatedPrincipalHolder.set(principalFor(role));
			try {
				for (DomainArea domainArea : DomainArea.values()) {
					for (PermissionAction action : PermissionAction.values()) {
						assertThat(service.hasPermission(domainArea, action))
							.as("%s must be denied for %s/%s", role, domainArea, action)
							.isFalse();
					}
				}
			}
			finally {
				AuthenticatedPrincipalHolder.clear();
			}
		}
	}

	@Test
	void stringOverloadMirrorsEnumOverloadForAValidPairAndDeniesUnrecognizedValuesWithoutThrowing() {
		AuthenticatedPrincipalHolder.set(principalFor(Role.TENANT_ADMIN));
		try {
			boolean enumResult = service.hasPermission(DomainArea.STUDENTS, PermissionAction.VIEW);
			boolean stringResult = service.hasPermission("STUDENTS", "VIEW");
			assertThat(stringResult).isEqualTo(enumResult);
			assertThat(stringResult).isTrue();

			assertThat(service.hasPermission("NOT_A_REAL_DOMAIN_AREA", "VIEW")).isFalse();
			assertThat(service.hasPermission("STUDENTS", "NOT_A_REAL_ACTION")).isFalse();
			assertThat(service.hasPermission("NOT_A_REAL_DOMAIN_AREA", "NOT_A_REAL_ACTION")).isFalse();
			assertThat(service.hasPermission("", "")).isFalse();
		}
		finally {
			AuthenticatedPrincipalHolder.clear();
		}
	}

	@Test
	void requirePermissionThrowsAccessDeniedOnDenialAndReturnsNormallyOnGrant() {
		AuthenticatedPrincipalHolder.set(principalFor(Role.READ_ONLY_AUDITOR));
		try {
			assertThatThrownBy(() -> service.requirePermission(DomainArea.STUDENTS, PermissionAction.DELETE))
				.isInstanceOf(AccessDeniedException.class);

			assertThatCode(() -> service.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW))
				.doesNotThrowAnyException();
		}
		finally {
			AuthenticatedPrincipalHolder.clear();
		}
	}

	// ------------------------------------------------------------------
	// Test data source: independent transcription of
	// docs/requirements/user-roles-and-permissions.md §2's table.
	// ------------------------------------------------------------------

	private static Stream<Arguments> matrixCases() {
		List<Arguments> args = new ArrayList<>();
		for (Map.Entry<Role, Map<DomainArea, Set<PermissionAction>>> roleRow : DOC_MATRIX.entrySet()) {
			Role role = roleRow.getKey();
			Map<DomainArea, Set<PermissionAction>> row = roleRow.getValue();
			for (DomainArea domainArea : DomainArea.values()) {
				Set<PermissionAction> cell = row.getOrDefault(domainArea, Set.of());
				if (cell.isEmpty()) {
					// A blank ("-") cell in the doc: no action is granted. VIEW is
					// the representative "weakest" action asserted false here - if
					// even VIEW is denied, the whole cell is confirmed empty.
					args.add(Arguments.of(role, domainArea, PermissionAction.VIEW, false));
				}
				else {
					for (PermissionAction action : cell) {
						args.add(Arguments.of(role, domainArea, action, true));
					}
				}
			}
		}
		return args.stream();
	}

	private static AuthenticatedPrincipal principalFor(Role role) {
		return new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(), role.name(), UUID.randomUUID());
	}

	private static Map.Entry<DomainArea, Set<PermissionAction>> cell(DomainArea domainArea,
			PermissionAction... actions) {
		return Map.entry(domainArea, EnumSet.copyOf(Arrays.asList(actions)));
	}

	@SafeVarargs
	private static Map<DomainArea, Set<PermissionAction>> row(Map.Entry<DomainArea, Set<PermissionAction>>... cells) {
		Map<DomainArea, Set<PermissionAction>> row = new EnumMap<>(DomainArea.class);
		for (Map.Entry<DomainArea, Set<PermissionAction>> entry : cells) {
			row.put(entry.getKey(), entry.getValue());
		}
		return Map.copyOf(row);
	}

	/**
	 * Row-by-row transcription of §2's table, in the exact column order the
	 * doc lists them (Institute Owner/Tenant Admin, Finance Staff, Course
	 * Coordinator, Student Support, Content Manager, Exam Manager, Attendance
	 * Operator, Read-only Auditor). Only the base action letter is transcribed
	 * for a handful of cells that carry a parenthetical qualifier this flat
	 * V/C/E/D/A model cannot represent (Devices' "V (request only)" for
	 * Student Support, Access & expiry's "V (approve if finance-adjacent)" for
	 * Finance Staff, Audit log's "V (own-area actions)"/"V (full)" for every
	 * role) - flagged inline, same limitation the implementation's own javadoc
	 * documents.
	 */
	private static final Map<Role, Map<DomainArea, Set<PermissionAction>>> DOC_MATRIX = Map.ofEntries(
			Map.entry(Role.TENANT_ADMIN,
					row(cell(DomainArea.STUDENTS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.DELETE),
							cell(DomainArea.TEACHERS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.DELETE),
							cell(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
									PermissionAction.DELETE),
							cell(DomainArea.COURSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.DELETE),
							cell(DomainArea.MATERIALS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.DELETE),
							cell(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
									PermissionAction.APPROVE),
							cell(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
									PermissionAction.DELETE),
							cell(DomainArea.ATTENDANCE, PermissionAction.VIEW, PermissionAction.CREATE_EDIT),
							cell(DomainArea.EXAMS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.APPROVE),
							cell(DomainArea.DEVICES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT),
							cell(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
									PermissionAction.APPROVE),
							cell(DomainArea.REVIEWS_MODERATION, PermissionAction.VIEW, PermissionAction.APPROVE),
							cell(DomainArea.AUDIT_LOG, PermissionAction.VIEW),
							cell(DomainArea.BRANDING_SETTINGS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT),
							cell(DomainArea.SUPPORT_TICKETS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
									PermissionAction.DELETE))),
			Map.entry(Role.FINANCE_STAFF,
					row(cell(DomainArea.STUDENTS, PermissionAction.VIEW), cell(DomainArea.COURSES, PermissionAction.VIEW),
							cell(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
									PermissionAction.APPROVE),
							cell(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
									PermissionAction.DELETE),
							// "V (approve if finance-adjacent)" -> base V only.
							cell(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW),
							// "V (own-area actions)" -> base V only.
							cell(DomainArea.AUDIT_LOG, PermissionAction.VIEW))),
			Map.entry(Role.COURSE_COORDINATOR,
					row(cell(DomainArea.STUDENTS, PermissionAction.VIEW),
							cell(DomainArea.TEACHERS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT),
							cell(DomainArea.COURSES, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.APPROVE),
							cell(DomainArea.MATERIALS, PermissionAction.VIEW),
							cell(DomainArea.REVIEWS_MODERATION, PermissionAction.VIEW, PermissionAction.APPROVE),
							cell(DomainArea.AUDIT_LOG, PermissionAction.VIEW))),
			Map.entry(Role.STUDENT_SUPPORT,
					row(cell(DomainArea.STUDENTS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT),
							cell(DomainArea.TEACHERS, PermissionAction.VIEW), cell(DomainArea.COURSES, PermissionAction.VIEW),
							cell(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW),
							// "V (request only)" -> base V only.
							cell(DomainArea.DEVICES, PermissionAction.VIEW),
							cell(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW), cell(DomainArea.AUDIT_LOG, PermissionAction.VIEW),
							cell(DomainArea.SUPPORT_TICKETS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT))),
			Map.entry(Role.CONTENT_MANAGER,
					row(cell(DomainArea.STUDENTS, PermissionAction.VIEW), cell(DomainArea.COURSES, PermissionAction.VIEW),
							cell(DomainArea.MATERIALS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT,
									PermissionAction.DELETE),
							cell(DomainArea.AUDIT_LOG, PermissionAction.VIEW))),
			Map.entry(Role.EXAM_MANAGER,
					row(cell(DomainArea.STUDENTS, PermissionAction.VIEW), cell(DomainArea.COURSES, PermissionAction.VIEW),
							cell(DomainArea.EXAMS, PermissionAction.VIEW, PermissionAction.CREATE_EDIT, PermissionAction.APPROVE),
							cell(DomainArea.AUDIT_LOG, PermissionAction.VIEW))),
			Map.entry(Role.ATTENDANCE_OPERATOR,
					row(cell(DomainArea.STUDENTS, PermissionAction.VIEW), cell(DomainArea.COURSES, PermissionAction.VIEW),
							cell(DomainArea.ATTENDANCE, PermissionAction.VIEW, PermissionAction.CREATE_EDIT),
							cell(DomainArea.AUDIT_LOG, PermissionAction.VIEW))),
			Map.entry(Role.READ_ONLY_AUDITOR,
					// Every single domain area is "V" for this role, per the doc's
					// dedicated closing note that this column is read-only across the
					// board.
					row(cell(DomainArea.STUDENTS, PermissionAction.VIEW), cell(DomainArea.TEACHERS, PermissionAction.VIEW),
							cell(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW), cell(DomainArea.COURSES, PermissionAction.VIEW),
							cell(DomainArea.MATERIALS, PermissionAction.VIEW), cell(DomainArea.PAYMENTS_SLIPS, PermissionAction.VIEW),
							cell(DomainArea.FINANCE_EXPENSES, PermissionAction.VIEW),
							cell(DomainArea.ATTENDANCE, PermissionAction.VIEW), cell(DomainArea.EXAMS, PermissionAction.VIEW),
							cell(DomainArea.DEVICES, PermissionAction.VIEW), cell(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW),
							cell(DomainArea.REVIEWS_MODERATION, PermissionAction.VIEW),
							cell(DomainArea.AUDIT_LOG, PermissionAction.VIEW),
							cell(DomainArea.BRANDING_SETTINGS, PermissionAction.VIEW),
							cell(DomainArea.SUPPORT_TICKETS, PermissionAction.VIEW))));

}
