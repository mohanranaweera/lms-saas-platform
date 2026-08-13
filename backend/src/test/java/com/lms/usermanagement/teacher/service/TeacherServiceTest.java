package com.lms.usermanagement.teacher.service;

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

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.ProvisionedUser;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.usermanagement.teacher.domain.ApprovalStatus;
import com.lms.usermanagement.teacher.domain.TeacherProfile;
import com.lms.usermanagement.teacher.repository.TeacherProfileRepository;
import java.lang.reflect.Field;
import java.time.Instant;
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
 * Mockito-only unit coverage for {@link TeacherService}, mirroring {@code
 * StaffServiceTest}'s structure and rigor level: the permission-check-first
 * defense-in-depth pattern for every public method, plus this module's own
 * two extra invariants Staff has no equivalent of - the always-suspended
 * creation flow (login-gate mechanism) and the Tenant-Admin-only
 * approve/reject narrowing on top of the shared {@code TEACHERS}/{@code
 * CREATE_EDIT} grant.
 */
@ExtendWith(MockitoExtension.class)
class TeacherServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private UserProvisioningApi userProvisioningApi;

	@Mock
	private TeacherProfileRepository teacherProfileRepository;

	@Mock
	private TenantContext tenantContext;

	@Mock
	private PermissionCheckService permissionCheckService;

	private TeacherService teacherService;

	@BeforeEach
	void setUp() {
		teacherService = new TeacherService(userProvisioningApi, teacherProfileRepository, tenantContext,
				permissionCheckService);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	// ------------------------------------------------------------------
	// createTeacher: always suspends the freshly-provisioned account.
	// ------------------------------------------------------------------

	@Test
	void createTeacherAlwaysProvisionsAsTeacherAndImmediatelySuspendsTheNewAccount() {
		UUID provisionedUserId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(userProvisioningApi.existsByEmail("teacher@example.test")).thenReturn(false);
		when(userProvisioningApi.provisionTenantUser("teacher@example.test", "raw-password-1!", "TEACHER", true))
			.thenReturn(new ProvisionedUser(provisionedUserId, "teacher@example.test"));
		when(teacherProfileRepository.save(any(TeacherProfile.class))).thenAnswer(invocation -> {
			TeacherProfile profile = invocation.getArgument(0);
			setId(profile, UUID.randomUUID());
			return profile;
		});

		TeacherAccount account = teacherService.createTeacher("Teacher Name", "teacher@example.test",
				"raw-password-1!");

		verify(userProvisioningApi).provisionTenantUser("teacher@example.test", "raw-password-1!", "TEACHER", true);
		verify(userProvisioningApi).suspendTenantUser(provisionedUserId);
		assertThat(account.name()).isEqualTo("Teacher Name");
		assertThat(account.email()).isEqualTo("teacher@example.test");
		assertThat(account.approvalStatus()).isEqualTo("PENDING");
		assertThat(account.accountStatus()).isEqualTo("SUSPENDED");
	}

	@Test
	void aDuplicateEmailPreCheckThrowsConflictWithoutEverCallingProvisionTenantUser() {
		when(userProvisioningApi.existsByEmail("dup@example.test")).thenReturn(true);

		assertThatThrownBy(() -> teacherService.createTeacher("Teacher Name", "dup@example.test", "raw-password-1!"))
			.isInstanceOf(ConflictException.class);

		verify(userProvisioningApi, never()).provisionTenantUser(anyString(), anyString(), anyString(), eq(true));
		verify(userProvisioningApi, never()).suspendTenantUser(any());
		verify(teacherProfileRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// Defense-in-depth: every public method independently re-checks
	// TEACHERS permission before touching any collaborator.
	// ------------------------------------------------------------------

	@Test
	void createTeacherChecksPermissionFirstAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.TEACHERS, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(
				() -> teacherService.createTeacher("Teacher Name", "teacher@example.test", "raw-password-1!"))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(userProvisioningApi);
		verify(teacherProfileRepository, never()).save(any());
	}

	@Test
	void approveTeacherChecksPermissionFirstAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.TEACHERS, PermissionAction.CREATE_EDIT);
		UUID teacherId = UUID.randomUUID();

		assertThatThrownBy(() -> teacherService.approveTeacher(teacherId)).isInstanceOf(AccessDeniedException.class);

		verify(teacherProfileRepository, never()).findById(any());
		verify(teacherProfileRepository, never()).save(any());
		verifyNoInteractions(userProvisioningApi);
	}

	@Test
	void rejectTeacherChecksPermissionFirstAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.TEACHERS, PermissionAction.CREATE_EDIT);
		UUID teacherId = UUID.randomUUID();

		assertThatThrownBy(() -> teacherService.rejectTeacher(teacherId)).isInstanceOf(AccessDeniedException.class);

		verify(teacherProfileRepository, never()).findById(any());
		verify(teacherProfileRepository, never()).save(any());
		verifyNoInteractions(userProvisioningApi);
	}

	@Test
	void listTeachersChecksViewPermissionAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.TEACHERS, PermissionAction.VIEW);

		assertThatThrownBy(() -> teacherService.listTeachers(null)).isInstanceOf(AccessDeniedException.class);

		verify(teacherProfileRepository, never()).findAll();
		verify(teacherProfileRepository, never()).findByApprovalStatus(any());
		verifyNoInteractions(userProvisioningApi);
	}

	@Test
	void getTeacherChecksViewPermissionAndPropagatesAccessDeniedWithoutTouchingAnyCollaborator() {
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.TEACHERS, PermissionAction.VIEW);
		UUID teacherId = UUID.randomUUID();

		assertThatThrownBy(() -> teacherService.getTeacher(teacherId)).isInstanceOf(AccessDeniedException.class);

		verify(teacherProfileRepository, never()).findById(any());
		verifyNoInteractions(userProvisioningApi);
	}

	@Test
	void listTeachersRequiresViewPermissionOnTheHappyPathToo() {
		when(teacherProfileRepository.findAll()).thenReturn(List.of());

		List<TeacherAccount> result = teacherService.listTeachers(null);

		assertThat(result).isEmpty();
		verify(permissionCheckService).requirePermission(DomainArea.TEACHERS, PermissionAction.VIEW);
	}

	@Test
	void getTeacherRequiresViewPermissionOnTheHappyPathToo() {
		UUID teacherId = UUID.randomUUID();
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> teacherService.getTeacher(teacherId)).isInstanceOf(NotFoundException.class);

		verify(permissionCheckService).requirePermission(DomainArea.TEACHERS, PermissionAction.VIEW);
	}

	// ------------------------------------------------------------------
	// Tenant-Admin-only approve/reject narrowing (approved plan §2).
	// ------------------------------------------------------------------

	@Test
	void tenantAdminSucceedsOnApproveAndReachesThePersistenceAndProvisioningCalls() {
		AuthenticatedPrincipalHolder.set(principal("TENANT_ADMIN"));
		UUID teacherId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		TeacherProfile profile = new TeacherProfile(TENANT_ID, userId, "Pending Teacher");
		setId(profile, teacherId);
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.of(profile));
		when(teacherProfileRepository.save(any(TeacherProfile.class))).thenAnswer(inv -> inv.getArgument(0));
		when(userProvisioningApi.findTenantUserSummaries(List.of(userId)))
			.thenReturn(List.of(new TenantUserSummary(userId, "teacher@example.test", "TEACHER", "ACTIVE")));

		TeacherAccount account = teacherService.approveTeacher(teacherId);

		assertThat(account.approvalStatus()).isEqualTo("APPROVED");
		verify(userProvisioningApi).activateTenantUser(userId);
		verify(teacherProfileRepository).save(profile);
	}

	@Test
	void tenantAdminSucceedsOnRejectAndReachesThePersistenceCallWithoutReactivatingTheAccount() {
		AuthenticatedPrincipalHolder.set(principal("TENANT_ADMIN"));
		UUID teacherId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		TeacherProfile profile = new TeacherProfile(TENANT_ID, userId, "Pending Teacher");
		setId(profile, teacherId);
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.of(profile));
		when(teacherProfileRepository.save(any(TeacherProfile.class))).thenAnswer(inv -> inv.getArgument(0));
		when(userProvisioningApi.findTenantUserSummaries(List.of(userId)))
			.thenReturn(List.of(new TenantUserSummary(userId, "teacher@example.test", "TEACHER", "SUSPENDED")));

		TeacherAccount account = teacherService.rejectTeacher(teacherId);

		assertThat(account.approvalStatus()).isEqualTo("REJECTED");
		verify(userProvisioningApi, never()).activateTenantUser(any());
		verify(teacherProfileRepository).save(profile);
	}

	@Test
	void courseCoordinatorIsDeniedOnApproveDespiteHoldingCreateEditAndNeverTouchesPersistenceOrProvisioning() {
		AuthenticatedPrincipalHolder.set(principal("COURSE_COORDINATOR"));
		UUID teacherId = UUID.randomUUID();

		assertThatThrownBy(() -> teacherService.approveTeacher(teacherId)).isInstanceOf(AccessDeniedException.class);

		verify(teacherProfileRepository, never()).findById(any());
		verify(teacherProfileRepository, never()).save(any());
		verifyNoInteractions(userProvisioningApi);
	}

	@Test
	void courseCoordinatorIsDeniedOnRejectDespiteHoldingCreateEditAndNeverTouchesPersistenceOrProvisioning() {
		AuthenticatedPrincipalHolder.set(principal("COURSE_COORDINATOR"));
		UUID teacherId = UUID.randomUUID();

		assertThatThrownBy(() -> teacherService.rejectTeacher(teacherId)).isInstanceOf(AccessDeniedException.class);

		verify(teacherProfileRepository, never()).findById(any());
		verify(teacherProfileRepository, never()).save(any());
		verifyNoInteractions(userProvisioningApi);
	}

	@Test
	void studentSupportIsDeniedOnApproveEvenThoughItOnlyHoldsViewOnTeachers() {
		AuthenticatedPrincipalHolder.set(principal("STUDENT_SUPPORT"));
		UUID teacherId = UUID.randomUUID();

		assertThatThrownBy(() -> teacherService.approveTeacher(teacherId)).isInstanceOf(AccessDeniedException.class);

		verify(teacherProfileRepository, never()).findById(any());
		verify(teacherProfileRepository, never()).save(any());
		verifyNoInteractions(userProvisioningApi);
	}

	// ------------------------------------------------------------------
	// One-directional, terminal approval transitions.
	// ------------------------------------------------------------------

	@Test
	void approvingAnAlreadyApprovedTeacherThrowsInvalidApprovalStateAndNeverSavesOrReactivates() {
		AuthenticatedPrincipalHolder.set(principal("TENANT_ADMIN"));
		UUID teacherId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		TeacherProfile profile = new TeacherProfile(TENANT_ID, userId, "Already Approved Teacher");
		setId(profile, teacherId);
		profile.approve(UUID.randomUUID(), Instant.now());
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> teacherService.approveTeacher(teacherId))
			.isInstanceOf(InvalidApprovalStateException.class);

		verify(teacherProfileRepository, never()).save(any());
		verify(userProvisioningApi, never()).activateTenantUser(any());
	}

	@Test
	void rejectingAnAlreadyApprovedTeacherThrowsInvalidApprovalStateAndNeverSaves() {
		AuthenticatedPrincipalHolder.set(principal("TENANT_ADMIN"));
		UUID teacherId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		TeacherProfile profile = new TeacherProfile(TENANT_ID, userId, "Already Approved Teacher");
		setId(profile, teacherId);
		profile.approve(UUID.randomUUID(), Instant.now());
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> teacherService.rejectTeacher(teacherId))
			.isInstanceOf(InvalidApprovalStateException.class);

		verify(teacherProfileRepository, never()).save(any());
		verify(userProvisioningApi, never()).activateTenantUser(any());
	}

	@Test
	void rejectingAnAlreadyRejectedTeacherThrowsInvalidApprovalStateAndNeverSaves() {
		AuthenticatedPrincipalHolder.set(principal("TENANT_ADMIN"));
		UUID teacherId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		TeacherProfile profile = new TeacherProfile(TENANT_ID, userId, "Already Rejected Teacher");
		setId(profile, teacherId);
		profile.reject(UUID.randomUUID(), Instant.now());
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> teacherService.rejectTeacher(teacherId))
			.isInstanceOf(InvalidApprovalStateException.class);

		verify(teacherProfileRepository, never()).save(any());
	}

	/**
	 * Plan §4/§21: {@code REJECTED -> APPROVED} reopening is explicitly
	 * forbidden - the transition is one-directional and terminal. Mirrors
	 * {@link #rejectingAnAlreadyRejectedTeacherThrowsInvalidApprovalStateAndNeverSaves()}
	 * but exercises the reverse direction (approve after reject).
	 */
	@Test
	void approvingAnAlreadyRejectedTeacherThrowsInvalidApprovalStateAndNeverSaves() {
		AuthenticatedPrincipalHolder.set(principal("TENANT_ADMIN"));
		UUID teacherId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		TeacherProfile profile = new TeacherProfile(TENANT_ID, userId, "Already Rejected Teacher");
		setId(profile, teacherId);
		profile.reject(UUID.randomUUID(), Instant.now());
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.of(profile));

		assertThatThrownBy(() -> teacherService.approveTeacher(teacherId))
			.isInstanceOf(InvalidApprovalStateException.class);

		verify(teacherProfileRepository, never()).save(any());
		verify(userProvisioningApi, never()).activateTenantUser(any());
	}

	// ------------------------------------------------------------------
	// Not-found / empty-summary edge cases.
	// ------------------------------------------------------------------

	@Test
	void getTeacherForANonexistentIdThrowsNotFound() {
		UUID teacherId = UUID.randomUUID();
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> teacherService.getTeacher(teacherId)).isInstanceOf(NotFoundException.class);
	}

	@Test
	void approveTeacherForANonexistentIdThrowsNotFound() {
		AuthenticatedPrincipalHolder.set(principal("TENANT_ADMIN"));
		UUID teacherId = UUID.randomUUID();
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> teacherService.approveTeacher(teacherId)).isInstanceOf(NotFoundException.class);

		verify(teacherProfileRepository, never()).save(any());
		verifyNoInteractions(userProvisioningApi);
	}

	@Test
	void listTeachersReturnsAnEmptyListWhenNoProfilesExist() {
		when(teacherProfileRepository.findAll()).thenReturn(List.of());

		List<TeacherAccount> result = teacherService.listTeachers(null);

		assertThat(result).isEmpty();
	}

	@Test
	void listTeachersWithAFilterDelegatesToFindByApprovalStatus() {
		when(teacherProfileRepository.findByApprovalStatus(ApprovalStatus.PENDING)).thenReturn(List.of());

		List<TeacherAccount> result = teacherService.listTeachers(ApprovalStatus.PENDING);

		assertThat(result).isEmpty();
		verify(teacherProfileRepository).findByApprovalStatus(ApprovalStatus.PENDING);
		verify(teacherProfileRepository, never()).findAll();
	}

	/**
	 * Data-integrity fallback: a {@code TeacherProfile} row whose {@code
	 * userId} no longer resolves to a {@code tenant_user} summary (composition
	 * across the module boundary) is silently omitted from a list rather than
	 * surfacing a broken/partial row.
	 */
	@Test
	void listTeachersSilentlyOmitsAProfileWhoseTenantUserSummaryIsMissing() {
		UUID teacherId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		TeacherProfile profile = new TeacherProfile(TENANT_ID, userId, "Orphaned Profile");
		setId(profile, teacherId);
		when(teacherProfileRepository.findAll()).thenReturn(List.of(profile));
		when(userProvisioningApi.findTenantUserSummaries(List.of(userId))).thenReturn(List.of());

		List<TeacherAccount> result = teacherService.listTeachers(null);

		assertThat(result).isEmpty();
	}

	/**
	 * Same data-integrity fallback for the single-resource read path: rather
	 * than leaking a partially-composed account, this surfaces as the same
	 * 404 a genuinely nonexistent id would produce.
	 */
	@Test
	void getTeacherThrowsNotFoundWhenTheTenantUserSummaryIsMissing() {
		UUID teacherId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		TeacherProfile profile = new TeacherProfile(TENANT_ID, userId, "Orphaned Profile");
		setId(profile, teacherId);
		when(teacherProfileRepository.findById(teacherId)).thenReturn(Optional.of(profile));
		when(userProvisioningApi.findTenantUserSummaries(List.of(userId))).thenReturn(List.of());

		assertThatThrownBy(() -> teacherService.getTeacher(teacherId)).isInstanceOf(NotFoundException.class);
	}

	// ------------------------------------------------------------------
	// Test fixtures.
	// ------------------------------------------------------------------

	private static AuthenticatedPrincipal principal(String role) {
		return new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, role, UUID.randomUUID());
	}

	/**
	 * {@link TeacherProfile}'s id (inherited from {@code BaseEntity}) has no
	 * public setter - populated by Hibernate's UUIDv7 generator at real
	 * persist time. Reflection is used here purely to build a saved fixture
	 * for this unit test, mirroring {@code StaffServiceTest#setId} exactly.
	 */
	private static void setId(TeacherProfile profile, UUID id) {
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
