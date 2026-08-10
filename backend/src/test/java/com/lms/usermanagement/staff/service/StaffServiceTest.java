package com.lms.usermanagement.staff.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.staff.domain.StaffProfile;
import com.lms.usermanagement.staff.repository.StaffProfileRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link StaffService}'s role-restriction
 * boundary: the exact 7 {@code ASSIGNABLE_STAFF_ROLES} codes are the only
 * ones that ever reach {@link UserProvisioningApi#provisionTenantUser}; every
 * other {@code Role} enum value (and any garbage string) is rejected before
 * that mock is ever touched. This is the defense-in-depth boundary worth a
 * precise unit test, distinct from (and faster than) the equivalent
 * Testcontainers/HTTP-layer coverage in {@code StaffManagementIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class StaffServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private UserProvisioningApi userProvisioningApi;

	@Mock
	private StaffProfileRepository staffProfileRepository;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private PermissionCheckService permissionCheckService;

	private StaffService staffService;

	@BeforeEach
	void setUp() {
		staffService = new StaffService(userProvisioningApi, staffProfileRepository, tenantContext,
				permissionCheckService);
	}

	@ParameterizedTest
	@ValueSource(strings = { "FINANCE_STAFF", "COURSE_COORDINATOR", "STUDENT_SUPPORT", "CONTENT_MANAGER",
			"EXAM_MANAGER", "ATTENDANCE_OPERATOR", "READ_ONLY_AUDITOR" })
	void eachOfTheSevenAssignableStaffRolesIsAcceptedAndReachesProvisioning(String roleCode) {
		UUID provisionedUserId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(userProvisioningApi.existsByEmail("staff@example.test")).thenReturn(false);
		when(userProvisioningApi.provisionTenantUser("staff@example.test", "raw-password-1!", roleCode, true))
			.thenReturn(new ProvisionedUser(provisionedUserId, "staff@example.test"));
		when(staffProfileRepository.save(any(StaffProfile.class))).thenAnswer(invocation -> {
			StaffProfile profile = invocation.getArgument(0);
			setId(profile, UUID.randomUUID());
			return profile;
		});

		StaffAccount account = staffService.createStaff("Staff Name", "staff@example.test", "raw-password-1!",
				roleCode);

		assertThat(account.roleCode()).isEqualTo(roleCode);
		assertThat(account.email()).isEqualTo("staff@example.test");
		assertThat(account.status()).isEqualTo("ACTIVE");
		verify(userProvisioningApi).provisionTenantUser("staff@example.test", "raw-password-1!", roleCode, true);
		verify(permissionCheckService).requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.CREATE_EDIT);
	}

	@ParameterizedTest
	@ValueSource(strings = { "TENANT_ADMIN", "TEACHER", "TEACHER_ASSISTANT", "STUDENT", "NOT_A_REAL_ROLE_AT_ALL" })
	void eachDisallowedRoleCodeIsRejectedBeforeTouchingUserProvisioningApi(String roleCode) {
		assertThatThrownBy(
				() -> staffService.createStaff("Staff Name", "staff@example.test", "raw-password-1!", roleCode))
			.isInstanceOf(InvalidStaffRoleException.class);

		verifyNoInteractions(userProvisioningApi);
		verify(staffProfileRepository, never()).save(any());
	}

	@Test
	void aDuplicateEmailPreCheckThrowsConflictWithoutEverCallingProvisionTenantUser() {
		when(userProvisioningApi.existsByEmail("dup@example.test")).thenReturn(true);

		assertThatThrownBy(() -> staffService.createStaff("Staff Name", "dup@example.test", "raw-password-1!",
				"FINANCE_STAFF")).isInstanceOf(com.lms.common.error.ConflictException.class);

		verify(userProvisioningApi, never()).provisionTenantUser(anyString(), anyString(), anyString(), eq(true));
		verify(staffProfileRepository, never()).save(any());
	}

	/**
	 * Defense-in-depth coverage for the service-layer {@code
	 * requirePermission} calls added alongside {@code StaffController}'s
	 * {@code @PreAuthorize} gates. Without these three tests, deleting the
	 * {@code requirePermission(...)} line from any of {@code createStaff}/
	 * {@code listStaff}/{@code getStaff} would not fail this file, and the
	 * HTTP-layer {@code @PreAuthorize} in {@code StaffManagementIntegrationTest}
	 * would mask the regression too - each test below stubs {@link
	 * PermissionCheckService} to deny and asserts both that the resulting
	 * {@link AccessDeniedException} propagates AND that no collaborator past
	 * the permission check is ever touched.
	 */
	@Test
	void createStaffChecksPermissionFirstAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(() -> staffService.createStaff("Staff Name", "staff@example.test", "raw-password-1!",
				"FINANCE_STAFF")).isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(userProvisioningApi);
		verify(staffProfileRepository, never()).save(any());
	}

	@Test
	void listStaffChecksViewPermissionAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW);

		assertThatThrownBy(() -> staffService.listStaff()).isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(userProvisioningApi);
		verify(staffProfileRepository, never()).findAll();
	}

	@Test
	void getStaffChecksViewPermissionAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW);
		UUID staffId = UUID.randomUUID();

		assertThatThrownBy(() -> staffService.getStaff(staffId)).isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(userProvisioningApi);
		verify(staffProfileRepository, never()).findById(any());
	}

	@Test
	void listStaffRequiresViewPermissionOnTheHappyPathToo() {
		when(staffProfileRepository.findAll()).thenReturn(List.of());

		staffService.listStaff();

		verify(permissionCheckService).requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW);
	}

	@Test
	void getStaffRequiresViewPermissionOnTheHappyPathToo() {
		UUID staffId = UUID.randomUUID();
		when(staffProfileRepository.findById(staffId)).thenReturn(java.util.Optional.empty());

		assertThatThrownBy(() -> staffService.getStaff(staffId))
			.isInstanceOf(com.lms.common.error.NotFoundException.class);

		verify(permissionCheckService).requirePermission(DomainArea.STAFF_AND_ROLES, PermissionAction.VIEW);
	}

	/**
	 * {@link StaffProfile}'s id (inherited from {@code BaseEntity}) has no
	 * public setter - populated by Hibernate's UUIDv7 generator at real
	 * persist time. Reflection is used here purely to build a saved fixture
	 * for this unit test, mirroring the same pattern already established in
	 * {@code AuthenticationServiceTest#setStatus}.
	 */
	private static void setId(StaffProfile profile, UUID id) {
		try {
			Field field = com.lms.common.persistence.BaseEntity.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(profile, id);
		}
		catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

}
