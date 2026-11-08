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
	void teacherConfigurableSetIsExactlyTheSpecifiedSix() {
		Set<Capability> teacher = Stream.of(Capability.values())
			.filter(Capability::isTeacherConfigurable)
			.collect(Collectors.toSet());
		assertThat(teacher).containsExactlyInAnyOrder(Capability.COURSES_VIEW, Capability.COURSES_VIEW_STUDENTS,
				Capability.MODULES_VIEW, Capability.MODULES_ADD, Capability.MODULES_DELETE, Capability.EXAMS_VIEW);
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
		verify(store, never()).findOverridesForRole(anyString());
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
				"STAFF_VIEW", true, "TUTE_CREATE", true));
		as(Role.OPERATOR);
		assertThat(service.hasCapability(Capability.STUDENTS_VIEW)).isTrue();
		assertThat(service.hasCapability("STUDENTS_VIEW")).isTrue();
		assertThat(service.hasCapability(Capability.STUDENTS_ADD)).isFalse();
		assertThat(service.hasCapability(Capability.COURSES_VIEW)).isFalse();
		// A stray row for a locked / unavailable key never grants anything.
		assertThat(service.hasCapability(Capability.STAFF_VIEW)).isFalse();
		assertThat(service.hasCapability(Capability.TUTE_CREATE)).isFalse();
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
