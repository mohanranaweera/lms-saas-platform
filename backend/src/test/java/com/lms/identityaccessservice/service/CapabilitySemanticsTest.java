package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.CapabilityAvailability;
import com.lms.identityaccessservice.api.CapabilityGate;
import com.lms.identityaccessservice.api.ConfigurableRole;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionPair;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.repository.CapabilityGrantStore;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * STAFF-MGMT-02 §2.2/§2.3 unit coverage: the capability catalog's invariants and
 * {@code hasCapability}/{@code hasPermission} semantics per role (no Spring context).
 */
class CapabilitySemanticsTest {

	private static final UUID TENANT = UUID.randomUUID();

	private final CapabilityGrantStore store = mock(CapabilityGrantStore.class);

	private final PermissionCheckServiceImpl service = new PermissionCheckServiceImpl(store);

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
		TenantContextHolder.clear();
		CapabilityElevationHolder.clear();
	}

	private void as(Role role) {
		TenantContextHolder.set(TENANT);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT, role.name(),
				UUID.randomUUID()));
	}

	// ------------------------------------------------------------------ catalog invariants

	@Test
	void noConfigurableCapabilityCanExceedTenantAdmin() {
		for (Capability capability : Capability.values()) {
			if (capability.availability() == CapabilityAvailability.ENFORCED) {
				assertThat(capability.configurableFor()).as(capability.key()).isNotEmpty();
				for (PermissionPair pair : capability.implied()) {
					assertThat(PermissionCheckServiceImpl.staticMatrixGrants(Role.TENANT_ADMIN, pair))
						.as("%s implies %s which Tenant Admin lacks", capability, pair)
						.isTrue();
				}
				assertThat(capability.implied()).contains(capability.primary());
			}
			else {
				assertThat(capability.reason()).as(capability.key()).isNotBlank();
				assertThat(capability.configurableFor()).as(capability.key()).isEmpty();
			}
		}
	}

	@Test
	void teacherConfigurableSetIsExactlyTheSpecifiedSixPlusTheTuteGroupAndCoursePaymentStatus() {
		Set<Capability> teacher = Stream.of(Capability.values())
			.filter(Capability::isTeacherConfigurable)
			.collect(Collectors.toSet());
		Set<Capability> tutes = Stream.of(Capability.values())
			.filter(c -> c.group() == com.lms.identityaccessservice.api.CapabilityGroup.TUTE)
			.collect(Collectors.toSet());
		assertThat(tutes).hasSize(10);
		// Opt-in for Teacher: the tute group (TUTE-01) and course payment status (2026-11-10).
		Set<Capability> optIn = new java.util.HashSet<>(tutes);
		optIn.add(Capability.COURSES_VIEW_PAYMENT_STATUS);
		Set<Capability> expected = new java.util.HashSet<>(optIn);
		expected.addAll(Set.of(Capability.COURSES_VIEW, Capability.COURSES_VIEW_STUDENTS, Capability.MODULES_VIEW,
				Capability.MODULES_ADD, Capability.MODULES_DELETE, Capability.EXAMS_VIEW));
		assertThat(teacher).isEqualTo(expected);
		// The historical six keep their "granted by default" Teacher column; the opt-in set does not.
		for (Capability capability : teacher) {
			assertThat(PermissionCheckServiceImpl.defaultGrant(ConfigurableRole.TEACHER, capability))
				.as(capability.key())
				.isEqualTo(!optIn.contains(capability));
		}
	}

	@Test
	void staffCapabilitiesAreNeverConfigurable() {
		Stream.of(Capability.values()).filter(c -> c.key().startsWith("STAFF_")).forEach(c -> {
			assertThat(c.availability()).isEqualTo(CapabilityAvailability.LOCKED);
			for (ConfigurableRole role : ConfigurableRole.values()) {
				assertThat(c.isConfigurableFor(role)).isFalse();
			}
		});
	}

	// ------------------------------------------------------------------ static roles

	@Test
	void staticRolesHoldACapabilityExactlyWhenTheyHoldItsPrimaryGate() {
		for (Role role : EnumSet.of(Role.TENANT_ADMIN, Role.FINANCE_STAFF, Role.COURSE_COORDINATOR,
				Role.STUDENT_SUPPORT, Role.CONTENT_MANAGER, Role.EXAM_MANAGER, Role.ATTENDANCE_OPERATOR,
				Role.READ_ONLY_AUDITOR)) {
			as(role);
			for (Capability capability : Capability.values()) {
				boolean expected = capability.primary() != null
						&& capability.availability() != CapabilityAvailability.UNAVAILABLE
						&& service.hasPermission(capability.primary().area(), capability.primary().action())
						&& (capability.staticRoleAllowlist() == null
								|| capability.staticRoleAllowlist().contains(role.name()));
				assertThat(service.hasCapability(capability)).as("%s %s", role, capability).isEqualTo(expected);
			}
			AuthenticatedPrincipalHolder.clear();
		}
		// Tenant Admin is never configurable: its grants are never read.
		verify(store, never()).findOverridesForRole("TENANT_ADMIN");
	}

	@Test
	void staffActivityIsTenantAdminAndAuditorOnly() {
		as(Role.FINANCE_STAFF);
		assertThat(service.hasPermission(DomainArea.AUDIT_LOG, PermissionAction.VIEW)).isTrue();
		assertThat(service.hasCapability(Capability.STAFF_ACTIVITY_VIEW)).isFalse();
		AuthenticatedPrincipalHolder.clear();
		as(Role.READ_ONLY_AUDITOR);
		assertThat(service.hasCapability(Capability.STAFF_ACTIVITY_VIEW)).isTrue();
		assertThat(service.hasCapability(Capability.STAFF_MANAGE)).isFalse();
	}

	// ------------------------------------------------------------------ Operator / Manager

	@Test
	void operatorHoldsOnlyGrantedEnforcedCapabilities() {
		when(store.findOverridesForRole("OPERATOR")).thenReturn(Map.of("STUDENTS_VIEW", true, "STUDENTS_ADD", false,
				"STAFF_VIEW", true, "FINANCE_DASHBOARD_RECENT_PAYMENTS", true));
		as(Role.OPERATOR);
		assertThat(service.hasCapability(Capability.STUDENTS_VIEW)).isTrue();
		assertThat(service.hasCapability("STUDENTS_VIEW")).isTrue();
		assertThat(service.hasCapability(Capability.STUDENTS_ADD)).isFalse();
		assertThat(service.hasCapability(Capability.COURSES_VIEW)).isFalse();
		// A stray row for a locked / unavailable key never grants anything.
		assertThat(service.hasCapability(Capability.STAFF_VIEW)).isFalse();
		assertThat(service.hasCapability(Capability.FINANCE_DASHBOARD_RECENT_PAYMENTS)).isFalse();
		assertThat(service.effectiveCapabilities()).containsExactly(Capability.STUDENTS_VIEW);
		assertThat(service.hasCapability("NOPE")).isFalse();
		assertThat(service.hasCapability((String) null)).isFalse();
	}

	@Test
	void operatorHasNoStaticPermissionExceptPairsElevatedForTheCurrentRequest() {
		as(Role.MANAGER);
		for (DomainArea area : DomainArea.values()) {
			for (PermissionAction action : PermissionAction.values()) {
				assertThat(service.hasPermission(area, action)).isFalse();
			}
		}
		CapabilityElevationHolder.set(Capability.STUDENTS_EDIT.implied());
		assertThat(service.hasPermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT)).isTrue();
		// Implied pairs are minimal: Edit Student's code path checks CREATE_EDIT only.
		assertThat(service.hasPermission(DomainArea.STUDENTS, PermissionAction.VIEW)).isFalse();
		assertThat(service.hasPermission(DomainArea.STUDENTS, PermissionAction.DELETE)).isFalse();
		CapabilityElevationHolder.clear();
		assertThat(service.hasPermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT)).isFalse();
	}

	@Test
	void elevationNeverAppliesToStaticRoles() {
		as(Role.READ_ONLY_AUDITOR);
		CapabilityElevationHolder.set(Set.of(PermissionPair.of(DomainArea.STUDENTS, PermissionAction.DELETE)));
		assertThat(service.hasPermission(DomainArea.STUDENTS, PermissionAction.DELETE)).isFalse();
	}

	@Test
	void configurableRolesFailClosedWithoutATenantContextOrOnATenantMismatch() {
		when(store.findOverridesForRole(anyString())).thenReturn(Map.of("STUDENTS_VIEW", true));
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT, "OPERATOR",
				UUID.randomUUID()));
		assertThat(service.hasCapability(Capability.STUDENTS_VIEW)).isFalse();
		TenantContextHolder.set(UUID.randomUUID());
		assertThat(service.hasCapability(Capability.STUDENTS_VIEW)).isFalse();
		AuthenticatedPrincipalHolder.clear();
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT, "TEACHER",
				UUID.randomUUID()));
		assertThat(service.hasCapability(Capability.COURSES_VIEW)).isFalse();
		assertThatThrownBy(() -> service.requireCapability(Capability.COURSES_VIEW))
			.isInstanceOf(AccessDeniedException.class);
	}

	// ------------------------------------------------------------------ seeded sub-roles (STAFF-MGMT-03 §6.1)

	private static final Set<Role> SEEDED = EnumSet.of(Role.FINANCE_STAFF, Role.COURSE_COORDINATOR,
			Role.STUDENT_SUPPORT, Role.CONTENT_MANAGER, Role.EXAM_MANAGER, Role.ATTENDANCE_OPERATOR,
			Role.READ_ONLY_AUDITOR);

	@Test
	void seededRolesWithoutOverridesKeepExactlyTheirStaticAccess() {
		// No tenant override rows: rollout must not change anyone's access.
		when(store.findOverridesForRole(anyString())).thenReturn(Map.of());
		PermissionCheckServiceImpl staticOnly = new PermissionCheckServiceImpl();
		for (Role role : SEEDED) {
			ConfigurableRole column = ConfigurableRole.valueOf(role.name());
			assertThat(column.isSeeded()).isTrue();
			as(role);
			for (Capability capability : Capability.values()) {
				if (capability.availability() != CapabilityAvailability.ENFORCED) {
					continue;
				}
				// Default = the static matrix holds the primary pair (the only ENFORCED capability with
				// an extra static allowlist is Enroll Students, whose path also needs PAYMENTS_SLIPS/APPROVE).
				boolean expected = service.hasPermission(capability.primary().area(), capability.primary().action())
						&& (capability.staticRoleAllowlist() == null
								|| capability.staticRoleAllowlist().contains(role.name()));
				assertThat(service.hasCapability(capability)).as("%s %s", role, capability).isEqualTo(expected);
				assertThat(PermissionCheckServiceImpl.defaultGrant(column, capability)).as("%s %s", role, capability)
					.isEqualTo(expected);
				// No override row: the interceptor is a no-op (no elevation, no denial).
				assertThat(service.capabilityGate(capability)).as("%s %s", role, capability)
					.isEqualTo(CapabilityGate.PASS);
			}
			for (DomainArea area : DomainArea.values()) {
				for (PermissionAction action : PermissionAction.values()) {
					PermissionPair pair = PermissionPair.of(area, action);
					assertThat(service.hasPermission(area, action)).as("%s %s", role, pair)
						.isEqualTo(PermissionCheckServiceImpl.staticMatrixGrants(role, pair));
					assertThat(service.isDelegated(area, action)).as("%s %s", role, pair).isFalse();
				}
			}
			// Same answers as the static-matrix-only instance.
			assertThat(service.effectiveCapabilities()).isEqualTo(staticOnly.effectiveCapabilities());
			AuthenticatedPrincipalHolder.clear();
		}
		assertThat(Stream.of(Capability.values())
			.filter(c -> c.availability() == CapabilityAvailability.ENFORCED && c.staticRoleAllowlist() != null))
			.containsExactly(Capability.COURSES_ENROLL_STUDENTS);
	}

	@Test
	void everyOperatorCapabilityIsConfigurableForTheSeededRolesAndTheAuditorOnlyForViewOnes() {
		for (Capability capability : Capability.values()) {
			boolean operator = capability.isConfigurableFor(ConfigurableRole.OPERATOR);
			boolean viewOnly = capability.implied().stream().allMatch(pair -> pair.action() == PermissionAction.VIEW);
			for (ConfigurableRole role : ConfigurableRole.values()) {
				if (!role.isSeeded()) {
					continue;
				}
				// TUTE-01 review L1: the label PDF (bulk addresses/phones) is the one VIEW
				// capability never configurable for the auditor.
				boolean auditorAllowed = viewOnly && capability != Capability.TUTE_DISPATCH_PRINT_LABELS;
				boolean expected = operator && (role != ConfigurableRole.READ_ONLY_AUDITOR || auditorAllowed);
				assertThat(capability.isConfigurableFor(role)).as("%s %s", role, capability).isEqualTo(expected);
			}
		}
		assertThat(Capability.STUDENTS_VIEW.isConfigurableFor(ConfigurableRole.READ_ONLY_AUDITOR)).isTrue();
		assertThat(Capability.STUDENTS_ADD.isConfigurableFor(ConfigurableRole.READ_ONLY_AUDITOR)).isFalse();
		assertThat(Capability.STUDENTS_ADD.isConfigurableFor(ConfigurableRole.FINANCE_STAFF)).isTrue();
		// Teacher's set is unchanged by the promotion.
		assertThat(Capability.STUDENTS_ADD.isTeacherConfigurable()).isFalse();
	}

	@Test
	void aSeededExplicitGrantElevatesAndAnExplicitRevokeDeniesOnlyTheAnnotatedEndpoint() {
		when(store.findOverridesForRole("FINANCE_STAFF"))
			.thenReturn(Map.of("PAYMENTS_APPROVE_PENDING", false, "COURSES_ADD", true));
		as(Role.FINANCE_STAFF);

		// Revoke: the annotated endpoint is denied...
		assertThat(service.capabilityGate(Capability.PAYMENTS_APPROVE_PENDING)).isEqualTo(CapabilityGate.DENY);
		assertThat(service.hasCapability(Capability.PAYMENTS_APPROVE_PENDING)).isFalse();
		// ...but unannotated endpoints keep the static matrix.
		assertThat(service.hasPermission(DomainArea.PAYMENTS_SLIPS, PermissionAction.APPROVE)).isTrue();
		// A sibling capability on the same pair is unaffected.
		assertThat(service.capabilityGate(Capability.PAYMENTS_UNDO)).isEqualTo(CapabilityGate.PASS);
		assertThat(service.hasCapability(Capability.PAYMENTS_UNDO)).isTrue();

		// Grant: elevates the implied pairs for the request only.
		assertThat(service.capabilityGate(Capability.COURSES_ADD)).isEqualTo(CapabilityGate.ELEVATE);
		assertThat(service.hasCapability(Capability.COURSES_ADD)).isTrue();
		assertThat(service.hasPermission(DomainArea.COURSES, PermissionAction.CREATE_EDIT)).isFalse();
		CapabilityElevationHolder.set(Capability.COURSES_ADD.implied());
		assertThat(service.hasPermission(DomainArea.COURSES, PermissionAction.CREATE_EDIT)).isTrue();
		assertThat(service.isDelegated(DomainArea.COURSES, PermissionAction.CREATE_EDIT)).isTrue();
		// A statically held pair is never "delegated".
		assertThat(service.isDelegated(DomainArea.COURSES, PermissionAction.VIEW)).isFalse();
		assertThat(service.hasPermission(DomainArea.COURSES, PermissionAction.DELETE)).isFalse();
		CapabilityElevationHolder.clear();
		assertThat(service.hasPermission(DomainArea.COURSES, PermissionAction.CREATE_EDIT)).isFalse();

		// No row: static default.
		assertThat(service.capabilityGate(Capability.PAYMENTS_VIEW)).isEqualTo(CapabilityGate.PASS);
		assertThat(service.hasCapability(Capability.STUDENTS_ADD)).isFalse();
	}

	@Test
	void readOnlyAuditorNeverGainsAWriteEvenWithAStrayRowOrElevation() {
		when(store.findOverridesForRole("READ_ONLY_AUDITOR"))
			.thenReturn(Map.of("STUDENTS_ADD", true, "STUDENTS_VIEW", false));
		as(Role.READ_ONLY_AUDITOR);
		assertThat(service.hasCapability(Capability.STUDENTS_ADD)).isFalse();
		assertThat(service.capabilityGate(Capability.STUDENTS_ADD)).isEqualTo(CapabilityGate.PASS);
		// View capabilities are configurable (revocable) for the auditor.
		assertThat(service.capabilityGate(Capability.STUDENTS_VIEW)).isEqualTo(CapabilityGate.DENY);
		CapabilityElevationHolder.set(Capability.STUDENTS_ADD.implied());
		assertThat(service.hasPermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT)).isFalse();
		assertThat(service.isDelegated(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT)).isFalse();
	}

	@Test
	void operatorAndManagerAreAlwaysDelegatedAndTheGateIsElevateOrDeny() {
		when(store.findOverridesForRole("OPERATOR")).thenReturn(Map.of("STUDENTS_VIEW", true));
		as(Role.OPERATOR);
		assertThat(service.capabilityGate(Capability.STUDENTS_VIEW)).isEqualTo(CapabilityGate.ELEVATE);
		assertThat(service.capabilityGate(Capability.STUDENTS_ADD)).isEqualTo(CapabilityGate.DENY);
		assertThat(service.isDelegated(DomainArea.COURSES, PermissionAction.CREATE_EDIT)).isTrue();
		AuthenticatedPrincipalHolder.clear();
		as(Role.TENANT_ADMIN);
		assertThat(service.capabilityGate(Capability.STUDENTS_VIEW)).isEqualTo(CapabilityGate.PASS);
		assertThat(service.isDelegated(DomainArea.COURSES, PermissionAction.CREATE_EDIT)).isFalse();
	}

	@Test
	void roleHoldsCapabilityUsesTheResolvedTenantsGrantsWithoutAPrincipal() {
		when(store.findOverridesForRole("MANAGER")).thenReturn(Map.of("STUDENTS_LOG_AS", true));
		TenantContextHolder.set(TENANT);
		assertThat(service.roleHoldsCapability("MANAGER", Capability.STUDENTS_LOG_AS)).isTrue();
		assertThat(service.roleHoldsCapability("OPERATOR", Capability.STUDENTS_LOG_AS)).isFalse();
		assertThat(service.roleHoldsCapability("TENANT_ADMIN", Capability.STUDENTS_LOG_AS)).isTrue();
		assertThat(service.roleHoldsCapability("STUDENT_SUPPORT", Capability.STUDENTS_LOG_AS)).isFalse();
		assertThat(service.roleHoldsCapability("STUDENT", Capability.STUDENTS_LOG_AS)).isFalse();
		assertThat(service.roleHoldsCapability("PLATFORM_ADMIN", Capability.STUDENTS_LOG_AS)).isFalse();
		assertThat(service.roleHoldsCapability(null, Capability.STUDENTS_LOG_AS)).isFalse();
		TenantContextHolder.clear();
		// No resolved tenant: configurable roles fail closed.
		assertThat(service.roleHoldsCapability("MANAGER", Capability.STUDENTS_LOG_AS)).isFalse();
	}

	// ------------------------------------------------------------------ Teacher and others

	@Test
	void teacherDefaultsToGrantedAndCanOnlyBeNarrowed() {
		when(store.findOverridesForRole("TEACHER")).thenReturn(Map.of("MODULES_DELETE", false, "STUDENTS_VIEW", true));
		as(Role.TEACHER);
		assertThat(service.hasCapability(Capability.COURSES_VIEW)).isTrue();
		assertThat(service.hasCapability(Capability.MODULES_DELETE)).isFalse();
		assertThat(service.hasCapability(Capability.STUDENTS_VIEW)).isFalse();
		assertThat(service.hasPermission(DomainArea.COURSES, PermissionAction.VIEW)).isFalse();
	}

	@Test
	void studentsAssistantsAndPlatformAdminHoldNothing() {
		for (String role : new String[] { "STUDENT", "TEACHER_ASSISTANT", "PLATFORM_ADMIN" }) {
			TenantContextHolder.set(TENANT);
			AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT, role,
					UUID.randomUUID()));
			assertThat(service.effectiveCapabilities()).as(role).isEmpty();
			AuthenticatedPrincipalHolder.clear();
		}
	}

}
