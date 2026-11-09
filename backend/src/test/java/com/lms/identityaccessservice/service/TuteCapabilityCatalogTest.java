package com.lms.identityaccessservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.CapabilityAvailability;
import com.lms.identityaccessservice.api.CapabilityGate;
import com.lms.identityaccessservice.api.CapabilityGroup;
import com.lms.identityaccessservice.api.ConfigurableRole;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionPair;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.repository.CapabilityGrantStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * TUTE-01 section 8: the Tute capability group - Tenant Admin only by default, configurable for
 * Operator / Manager / seeded roles (Read-only Auditor view-only) and Teacher with a default of
 * NOT granted, and no change to any older capability's Teacher default.
 */
class TuteCapabilityCatalogTest {

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

	private static List<Capability> tuteCapabilities() {
		return Stream.of(Capability.values()).filter(c -> c.group() == CapabilityGroup.TUTE).toList();
	}

	@Test
	void tuteGroupHasTheTenKeysEnforcedWithTheSpecifiedPairs() {
		Map<Capability, PermissionPair> expected = Map.of(Capability.TUTE_VIEW_TUTES,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.VIEW), Capability.TUTE_VIEW_TUTE,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.VIEW), Capability.TUTE_CREATE,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.CREATE_EDIT), Capability.TUTE_UPDATE,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.CREATE_EDIT), Capability.TUTE_ENROLL,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.CREATE_EDIT), Capability.TUTE_UNROLL,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.CREATE_EDIT), Capability.TUTE_UPDATE_STATUS,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.APPROVE), Capability.TUTE_DELETE,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.DELETE), Capability.TUTE_DISPATCH_VIEW,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.VIEW), Capability.TUTE_DISPATCH_PRINT_LABELS,
				PermissionPair.of(DomainArea.TUTES, PermissionAction.VIEW));
		assertThat(tuteCapabilities()).containsExactlyInAnyOrderElementsOf(expected.keySet());
		assertThat(Capability.TUTE_DISPATCH_VIEW.label()).isEqualTo("View Tute Dispatch");
		assertThat(Capability.TUTE_DISPATCH_PRINT_LABELS.label()).isEqualTo("Print Dispatch Labels");
		for (Capability capability : tuteCapabilities()) {
			assertThat(capability.availability()).as(capability.key()).isEqualTo(CapabilityAvailability.ENFORCED);
			assertThat(capability.primary()).as(capability.key()).isEqualTo(expected.get(capability));
			assertThat(capability.implied()).containsExactly(expected.get(capability));
			assertThat(capability.teacherDefaultGranted()).as(capability.key()).isFalse();
			for (ConfigurableRole role : ConfigurableRole.values()) {
				boolean configurable = role != ConfigurableRole.READ_ONLY_AUDITOR
						|| (capability.primary().action() == PermissionAction.VIEW
								&& capability != Capability.TUTE_DISPATCH_PRINT_LABELS);
				assertThat(capability.isConfigurableFor(role)).as("%s %s", capability, role).isEqualTo(configurable);
				assertThat(PermissionCheckServiceImpl.defaultGrant(role, capability)).as("%s %s", capability, role)
					.isFalse();
			}
		}
	}

	@Test
	void labelPrintingIsNeverConfigurableForTheAuditorWhileOtherViewCapabilitiesAre() {
		assertThat(Capability.TUTE_DISPATCH_PRINT_LABELS.isConfigurableFor(ConfigurableRole.READ_ONLY_AUDITOR)).isFalse();
		assertThat(Capability.TUTE_DISPATCH_PRINT_LABELS.isConfigurableFor(ConfigurableRole.FINANCE_STAFF)).isTrue();
		assertThat(Capability.TUTE_DISPATCH_VIEW.isConfigurableFor(ConfigurableRole.READ_ONLY_AUDITOR)).isTrue();
		// Every other capability's auditor rule is unchanged: configurable iff all implied pairs are VIEW.
		Stream.of(Capability.values())
			.filter(c -> c != Capability.TUTE_DISPATCH_PRINT_LABELS && c.isConfigurableFor(ConfigurableRole.OPERATOR))
			.forEach(c -> assertThat(c.isConfigurableFor(ConfigurableRole.READ_ONLY_AUDITOR)).as(c.key())
				.isEqualTo(c.implied().stream().allMatch(pair -> pair.action() == PermissionAction.VIEW)));
		when(store.findOverridesForRole("READ_ONLY_AUDITOR")).thenReturn(Map.of("TUTE_DISPATCH_PRINT_LABELS", true));
		as(Role.READ_ONLY_AUDITOR);
		assertThat(service.hasCapability(Capability.TUTE_DISPATCH_PRINT_LABELS)).isFalse();
		assertThat(service.capabilityGate(Capability.TUTE_DISPATCH_PRINT_LABELS)).isEqualTo(CapabilityGate.PASS);
	}

	@Test
	void onlyTenantAdminHoldsTheTutePairsStatically() {
		for (Role role : Role.values()) {
			for (PermissionAction action : PermissionAction.values()) {
				boolean expected = role == Role.TENANT_ADMIN;
				assertThat(PermissionCheckServiceImpl.staticMatrixGrants(role, PermissionPair.of(DomainArea.TUTES, action)))
					.as("%s %s", role, action)
					.isEqualTo(expected);
			}
		}
		as(Role.TENANT_ADMIN);
		tuteCapabilities().forEach(c -> assertThat(service.hasCapability(c)).as(c.key()).isTrue());
	}

	@Test
	void olderCapabilitiesKeepTheirTeacherDefault() {
		Stream.of(Capability.values())
			.filter(c -> c.group() != CapabilityGroup.TUTE)
			.forEach(c -> assertThat(c.teacherDefaultGranted()).as(c.key()).isTrue());
		assertThat(PermissionCheckServiceImpl.defaultGrant(ConfigurableRole.TEACHER, Capability.COURSES_VIEW)).isTrue();
	}

	@Test
	void teacherHoldsNoTuteCapabilityUntilGrantedAndAGrantNeverElevates() {
		when(store.findOverridesForRole("TEACHER")).thenReturn(Map.of());
		as(Role.TEACHER);
		for (Capability capability : tuteCapabilities()) {
			assertThat(service.hasCapability(capability)).as(capability.key()).isFalse();
			assertThat(service.capabilityGate(capability)).isEqualTo(CapabilityGate.DENY);
		}
		// Existing Teacher defaults are untouched.
		assertThat(service.hasCapability(Capability.COURSES_VIEW)).isTrue();

		when(store.findOverridesForRole("TEACHER")).thenReturn(Map.of("TUTE_VIEW_TUTES", true));
		assertThat(service.hasCapability(Capability.TUTE_VIEW_TUTES)).isTrue();
		assertThat(service.capabilityGate(Capability.TUTE_VIEW_TUTES)).isEqualTo(CapabilityGate.PASS);
		assertThat(service.hasCapability(Capability.TUTE_CREATE)).isFalse();
		assertThat(service.hasPermission(DomainArea.TUTES, PermissionAction.VIEW)).isFalse();
	}

	@Test
	void operatorNeedsAnExplicitGrantPerCapability() {
		when(store.findOverridesForRole("OPERATOR")).thenReturn(Map.of("TUTE_DISPATCH_VIEW", true));
		as(Role.OPERATOR);
		assertThat(service.hasCapability(Capability.TUTE_DISPATCH_VIEW)).isTrue();
		assertThat(service.capabilityGate(Capability.TUTE_DISPATCH_VIEW)).isEqualTo(CapabilityGate.ELEVATE);
		// Viewing the queue never implies printing labels or confirming dispatch.
		assertThat(service.hasCapability(Capability.TUTE_DISPATCH_PRINT_LABELS)).isFalse();
		assertThat(service.hasCapability(Capability.TUTE_UPDATE_STATUS)).isFalse();
		assertThat(service.capabilityGate(Capability.TUTE_DISPATCH_PRINT_LABELS)).isEqualTo(CapabilityGate.DENY);
	}

	@Test
	void seededRolesDefaultToNothingAndTheAuditorNeverGetsAWrite() {
		when(store.findOverridesForRole("READ_ONLY_AUDITOR"))
			.thenReturn(Map.of("TUTE_CREATE", true, "TUTE_VIEW_TUTES", true));
		as(Role.READ_ONLY_AUDITOR);
		// A stray write row is ignored (not configurable for the auditor) ...
		assertThat(service.hasCapability(Capability.TUTE_CREATE)).isFalse();
		assertThat(service.capabilityGate(Capability.TUTE_CREATE)).isEqualTo(CapabilityGate.PASS);
		// ... and the view grant elevates a VIEW pair only.
		assertThat(service.hasCapability(Capability.TUTE_VIEW_TUTES)).isTrue();
		assertThat(service.capabilityGate(Capability.TUTE_VIEW_TUTES)).isEqualTo(CapabilityGate.ELEVATE);
		AuthenticatedPrincipalHolder.clear();

		when(store.findOverridesForRole("COURSE_COORDINATOR")).thenReturn(Map.of());
		as(Role.COURSE_COORDINATOR);
		tuteCapabilities().forEach(c -> assertThat(service.hasCapability(c)).as(c.key()).isFalse());
	}

}
