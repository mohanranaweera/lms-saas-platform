package com.lms.usermanagement.student.service;

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
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.student.domain.StudentProfile;
import com.lms.usermanagement.student.repository.StudentProfileRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Mockito-only unit coverage for {@link StudentService}'s role-hardcoding and
 * permission-check boundaries: {@code createStudent} always calls {@link
 * UserProvisioningApi#provisionTenantUser} with the literal role {@code
 * "STUDENT"} and {@code mustChangePassword = true}, regardless of any
 * request content (there is no {@code roleCode} parameter on this method at
 * all - unlike {@code StaffServiceTest}'s equivalent, there is nothing to
 * restrict/reject). Also covers the self-service methods' no-id, no-
 * permission-check structural design.
 */
@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private UserProvisioningApi userProvisioningApi;

	@Mock
	private StudentProfileRepository studentProfileRepository;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private PermissionCheckService permissionCheckService;

	private StudentService studentService;

	@BeforeEach
	void setUp() {
		studentService = new StudentService(userProvisioningApi, studentProfileRepository, tenantContext,
				permissionCheckService);
	}

	@AfterEach
	void clearAuthenticatedPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void createStudentAlwaysProvisionsWithTheLiteralStudentRoleAndMustChangePasswordTrue() {
		UUID adminUserId = UUID.randomUUID();
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(adminUserId, TENANT_ID, "TENANT_ADMIN", null));
		UUID provisionedUserId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(userProvisioningApi.existsByEmail("student@example.test")).thenReturn(false);
		when(userProvisioningApi.provisionTenantUser("student@example.test", "raw-password-1!", "STUDENT", true))
			.thenReturn(new ProvisionedUser(provisionedUserId, "student@example.test"));
		when(studentProfileRepository.save(any(StudentProfile.class))).thenAnswer(invocation -> {
			StudentProfile profile = invocation.getArgument(0);
			setId(profile, UUID.randomUUID());
			return profile;
		});

		StudentAccount account = studentService.createStudent("Student Name", "student@example.test",
				"raw-password-1!");

		assertThat(account.roleCode()).isEqualTo("STUDENT");
		assertThat(account.email()).isEqualTo("student@example.test");
		assertThat(account.status()).isEqualTo("ACTIVE");
		verify(userProvisioningApi).provisionTenantUser("student@example.test", "raw-password-1!", "STUDENT", true);
		verify(permissionCheckService).requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
	}

	@Test
	void aDuplicateEmailPreCheckThrowsConflictWithoutEverCallingProvisionTenantUser() {
		when(userProvisioningApi.existsByEmail("dup@example.test")).thenReturn(true);

		assertThatThrownBy(() -> studentService.createStudent("Student Name", "dup@example.test", "raw-password-1!"))
			.isInstanceOf(com.lms.common.error.ConflictException.class);

		verify(userProvisioningApi, never()).provisionTenantUser(anyString(), anyString(), anyString(), eq(true));
		verify(studentProfileRepository, never()).save(any());
	}

	/**
	 * Defense-in-depth coverage for the service-layer {@code
	 * requirePermission} calls added alongside {@code StudentController}'s
	 * {@code @PreAuthorize} gates, mirroring {@code StaffServiceTest}'s
	 * equivalent three tests.
	 */
	@Test
	void createStudentChecksPermissionFirstAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(
				() -> studentService.createStudent("Student Name", "student@example.test", "raw-password-1!"))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(userProvisioningApi);
		verify(studentProfileRepository, never()).save(any());
	}

	@Test
	void listStudentsChecksViewPermissionAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);

		assertThatThrownBy(() -> studentService.listStudents()).isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(userProvisioningApi);
		verify(studentProfileRepository, never()).findAll();
	}

	@Test
	void getStudentChecksViewPermissionAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
		UUID studentId = UUID.randomUUID();

		assertThatThrownBy(() -> studentService.getStudent(studentId)).isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(userProvisioningApi);
		verify(studentProfileRepository, never()).findById(any());
	}

	@Test
	void updateStudentChecksCreateEditPermissionAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
		UUID studentId = UUID.randomUUID();

		assertThatThrownBy(() -> studentService.updateStudent(studentId, "New Name"))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(userProvisioningApi);
		verify(studentProfileRepository, never()).findById(any());
	}

	@Test
	void listStudentsRequiresViewPermissionOnTheHappyPathToo() {
		when(studentProfileRepository.findAll()).thenReturn(List.of());

		studentService.listStudents();

		verify(permissionCheckService).requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
	}

	@Test
	void getStudentRequiresViewPermissionOnTheHappyPathToo() {
		UUID studentId = UUID.randomUUID();
		when(studentProfileRepository.findById(studentId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> studentService.getStudent(studentId))
			.isInstanceOf(com.lms.common.error.NotFoundException.class);

		verify(permissionCheckService).requirePermission(DomainArea.STUDENTS, PermissionAction.VIEW);
	}

	@Test
	void updateStudentRenamesTheProfileAndRequiresCreateEditPermission() {
		UUID studentId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID adminUserId = UUID.randomUUID();
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(adminUserId, TENANT_ID, "STUDENT_SUPPORT", null));
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		StudentProfile profile = new StudentProfile(TENANT_ID, userId, "Old Name");
		setId(profile, studentId);
		when(studentProfileRepository.findById(studentId)).thenReturn(Optional.of(profile));
		when(userProvisioningApi.findTenantUserSummaries(List.of(userId)))
			.thenReturn(List.of(new TenantUserSummary(userId, "student@example.test", "STUDENT", "ACTIVE")));

		StudentAccount account = studentService.updateStudent(studentId, "New Name");

		assertThat(account.name()).isEqualTo("New Name");
		assertThat(profile.getName()).isEqualTo("New Name");
		verify(permissionCheckService).requirePermission(DomainArea.STUDENTS, PermissionAction.CREATE_EDIT);
	}

	/**
	 * Structural proof that the self-service methods take no id parameter at
	 * all - asserted at the method-signature level, per plan §18.
	 */
	@Test
	void getOwnProfileAndUpdateOwnProfileTakeNoIdParameter() throws NoSuchMethodException {
		assertThat(StudentService.class.getDeclaredMethod("getOwnProfile").getParameterCount()).isZero();
		assertThat(StudentService.class.getDeclaredMethod("updateOwnProfile", String.class).getParameterCount())
			.isEqualTo(1);
	}

	@Test
	void getOwnProfileNeverCallsPermissionCheckServiceAndResolvesFromTheAuthenticatedPrincipalOnly() {
		UUID userId = UUID.randomUUID();
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(userId, TENANT_ID, "STUDENT", null));
		StudentProfile profile = new StudentProfile(TENANT_ID, userId, "Self Student");
		UUID profileId = UUID.randomUUID();
		setId(profile, profileId);
		when(studentProfileRepository.findOne(any(org.springframework.data.jpa.domain.Specification.class)))
			.thenReturn(Optional.of(profile));
		when(userProvisioningApi.findTenantUserSummaries(List.of(userId)))
			.thenReturn(List.of(new TenantUserSummary(userId, "self@example.test", "STUDENT", "ACTIVE")));

		StudentAccount account = studentService.getOwnProfile();

		assertThat(account.id()).isEqualTo(profileId);
		assertThat(account.email()).isEqualTo("self@example.test");
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void getOwnProfileThrowsNotFoundWhenNoProfileMatchesTheAuthenticatedUserId() {
		UUID userId = UUID.randomUUID();
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(userId, TENANT_ID, "STUDENT", null));
		when(studentProfileRepository.findOne(any(org.springframework.data.jpa.domain.Specification.class)))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> studentService.getOwnProfile())
			.isInstanceOf(com.lms.common.error.NotFoundException.class);

		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void updateOwnProfileNeverCallsPermissionCheckServiceAndRenamesTheCallersOwnProfileOnly() {
		UUID userId = UUID.randomUUID();
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(userId, TENANT_ID, "STUDENT", null));
		StudentProfile profile = new StudentProfile(TENANT_ID, userId, "Old Self Name");
		setId(profile, UUID.randomUUID());
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(studentProfileRepository.findOne(any(org.springframework.data.jpa.domain.Specification.class)))
			.thenReturn(Optional.of(profile));
		when(userProvisioningApi.findTenantUserSummaries(List.of(userId)))
			.thenReturn(List.of(new TenantUserSummary(userId, "self@example.test", "STUDENT", "ACTIVE")));

		StudentAccount account = studentService.updateOwnProfile("New Self Name");

		assertThat(account.name()).isEqualTo("New Self Name");
		assertThat(profile.getName()).isEqualTo("New Self Name");
		verifyNoInteractions(permissionCheckService);
	}

	/**
	 * {@link StudentProfile}'s id (inherited from {@code BaseEntity}) has no
	 * public setter - populated by Hibernate's UUIDv7 generator at real
	 * persist time. Reflection is used here purely to build a saved fixture
	 * for this unit test, mirroring {@code StaffServiceTest}'s identical
	 * pattern.
	 */
	private static void setId(StudentProfile profile, UUID id) {
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
