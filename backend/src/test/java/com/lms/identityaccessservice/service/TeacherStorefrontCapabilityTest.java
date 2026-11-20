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
import com.lms.identityaccessservice.api.ConfigurableRole;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.repository.CapabilityGrantStore;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * STORE-01 / ADR-037 decision 1: {@code TEACHER_STOREFRONT_MANAGE} - Tenant
 * Admin granted; Operator/Manager configurable, default off; Teacher never
 * (not configurable, no default).
 */
class TeacherStorefrontCapabilityTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final Capability CAPABILITY = Capability.TEACHER_STOREFRONT_MANAGE;

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

	@Test
	void catalogEntry() {
		assertThat(CAPABILITY.key()).isEqualTo("TEACHER_STOREFRONT_MANAGE");
		assertThat(CAPABILITY.availability()).isEqualTo(CapabilityAvailability.ENFORCED);
		assertThat(CAPABILITY.staticRoleAllowlist()).isEqualTo(Set.of("TENANT_ADMIN"));
		assertThat(CAPABILITY.isConfigurableFor(ConfigurableRole.OPERATOR)).isTrue();
		assertThat(CAPABILITY.isConfigurableFor(ConfigurableRole.MANAGER)).isTrue();
		assertThat(CAPABILITY.isTeacherConfigurable()).isFalse();
		for (ConfigurableRole role : ConfigurableRole.values()) {
			if (role != ConfigurableRole.TEACHER) {
				// Teacher: not configurable at all, so no default applies (see hasCapability below).
				assertThat(PermissionCheckServiceImpl.defaultGrant(role, CAPABILITY)).as(role.name()).isFalse();
			}
		}
	}

	@Test
	void tenantAdminHoldsItAndTeacherNever() {
		as(Role.TENANT_ADMIN);
		assertThat(service.hasCapability(CAPABILITY)).isTrue();
		AuthenticatedPrincipalHolder.clear();

		when(store.findOverridesForRole("TEACHER")).thenReturn(Map.of("TEACHER_STOREFRONT_MANAGE", true));
		as(Role.TEACHER);
		// Even a stray override row cannot grant it: not teacher-configurable.
		assertThat(service.hasCapability(CAPABILITY)).isFalse();
		AuthenticatedPrincipalHolder.clear();

		when(store.findOverridesForRole("COURSE_COORDINATOR")).thenReturn(Map.of());
		as(Role.COURSE_COORDINATOR);
		assertThat(service.hasCapability(CAPABILITY)).isFalse();
	}

	@Test
	void operatorAndManagerAreOffByDefaultAndElevatedOnlyWithAGrant() {
		when(store.findOverridesForRole("OPERATOR")).thenReturn(Map.of());
		as(Role.OPERATOR);
		assertThat(service.hasCapability(CAPABILITY)).isFalse();
		assertThat(service.capabilityGate(CAPABILITY)).isEqualTo(CapabilityGate.DENY);
		AuthenticatedPrincipalHolder.clear();

		when(store.findOverridesForRole("MANAGER")).thenReturn(Map.of("TEACHER_STOREFRONT_MANAGE", true));
		as(Role.MANAGER);
		assertThat(service.hasCapability(CAPABILITY)).isTrue();
		assertThat(service.capabilityGate(CAPABILITY)).isEqualTo(CapabilityGate.ELEVATE);
	}

}
