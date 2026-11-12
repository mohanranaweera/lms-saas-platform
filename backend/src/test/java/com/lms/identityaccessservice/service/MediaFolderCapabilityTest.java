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
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionPair;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.repository.CapabilityGrantStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * MEDIA-FOLDERS-01 section 3: Manage / Share Media Folders are ENFORCED, Tenant Admin only by
 * default (Content Manager holds MATERIALS/CREATE_EDIT but gets nothing new), configurable for
 * Operator / Manager / seeded roles / Teacher with the Teacher default off. No other media
 * capability changes.
 */
class MediaFolderCapabilityTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final List<Capability> FOLDER_CAPABILITIES = List.of(Capability.MEDIA_MANAGE_FOLDERS,
			Capability.MEDIA_SHARE_FOLDERS);

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
	void folderCapabilitiesAreEnforcedTenantAdminOnlyAndTeacherOptIn() {
		for (Capability capability : FOLDER_CAPABILITIES) {
			assertThat(capability.availability()).isEqualTo(CapabilityAvailability.ENFORCED);
			assertThat(capability.primary())
				.isEqualTo(PermissionPair.of(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT));
			assertThat(capability.implied()).containsExactlyInAnyOrder(
					PermissionPair.of(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT),
					PermissionPair.of(DomainArea.MATERIALS, PermissionAction.VIEW));
			assertThat(capability.staticRoleAllowlist()).isEqualTo(Set.of("TENANT_ADMIN"));
			assertThat(capability.teacherDefaultGranted()).isFalse();
			assertThat(capability.isTeacherConfigurable()).isTrue();
			for (ConfigurableRole role : ConfigurableRole.values()) {
				assertThat(capability.isConfigurableFor(role)).as("%s %s", capability, role)
					.isEqualTo(role != ConfigurableRole.READ_ONLY_AUDITOR);
				assertThat(PermissionCheckServiceImpl.defaultGrant(role, capability)).as("%s %s", capability, role)
					.isFalse();
			}
		}
		// Unchanged neighbours.
		assertThat(Capability.MEDIA_ADD.teacherDefaultGranted()).isTrue();
		assertThat(Capability.MEDIA_ADD.staticRoleAllowlist()).isNull();
		assertThat(Capability.MEDIA_VIEW.staticRoleAllowlist()).isNull();
		assertThat(Capability.MEDIA_DELETE.staticRoleAllowlist()).isNull();
	}

	@Test
	void tenantAdminHoldsThemAndContentManagerDoesNotByDefault() {
		as(Role.TENANT_ADMIN);
		FOLDER_CAPABILITIES.forEach(c -> assertThat(service.hasCapability(c)).as(c.key()).isTrue());
		AuthenticatedPrincipalHolder.clear();

		when(store.findOverridesForRole("CONTENT_MANAGER")).thenReturn(Map.of());
		as(Role.CONTENT_MANAGER);
		assertThat(service.hasCapability(Capability.MEDIA_ADD)).isTrue();
		FOLDER_CAPABILITIES.forEach(c -> assertThat(service.hasCapability(c)).as(c.key()).isFalse());
		AuthenticatedPrincipalHolder.clear();

		// An explicit tenant grant gives it to the Content Manager.
		when(store.findOverridesForRole("CONTENT_MANAGER")).thenReturn(Map.of("MEDIA_MANAGE_FOLDERS", true));
		as(Role.CONTENT_MANAGER);
		assertThat(service.hasCapability(Capability.MEDIA_MANAGE_FOLDERS)).isTrue();
		assertThat(service.hasCapability(Capability.MEDIA_SHARE_FOLDERS)).isFalse();
	}

	@Test
	void teacherNeedsAnExplicitGrantAndOperatorIsElevatedOnlyWithOne() {
		when(store.findOverridesForRole("TEACHER")).thenReturn(Map.of());
		as(Role.TEACHER);
		for (Capability capability : FOLDER_CAPABILITIES) {
			assertThat(service.hasCapability(capability)).isFalse();
			assertThat(service.capabilityGate(capability)).isEqualTo(CapabilityGate.DENY);
		}
		when(store.findOverridesForRole("TEACHER")).thenReturn(Map.of("MEDIA_MANAGE_FOLDERS", true));
		assertThat(service.hasCapability(Capability.MEDIA_MANAGE_FOLDERS)).isTrue();
		assertThat(service.capabilityGate(Capability.MEDIA_MANAGE_FOLDERS)).isEqualTo(CapabilityGate.PASS);
		assertThat(service.hasCapability(Capability.MEDIA_SHARE_FOLDERS)).isFalse();
		AuthenticatedPrincipalHolder.clear();

		when(store.findOverridesForRole("OPERATOR")).thenReturn(Map.of("MEDIA_SHARE_FOLDERS", true));
		as(Role.OPERATOR);
		assertThat(service.capabilityGate(Capability.MEDIA_SHARE_FOLDERS)).isEqualTo(CapabilityGate.ELEVATE);
		assertThat(service.capabilityGate(Capability.MEDIA_MANAGE_FOLDERS)).isEqualTo(CapabilityGate.DENY);
	}

}
