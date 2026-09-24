package com.lms.videoaccessmanagement.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.common.error.NotFoundException;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.contentmanagement.api.MaterialVideoOwnership;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.enrollmentmanagement.api.EnrollmentAccessState;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
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
 * Mockito-only unit coverage for {@link VideoAccessGuard} - mirrors {@code
 * MaterialAccessGuardTest}'s structure exactly.
 */
@ExtendWith(MockitoExtension.class)
class VideoAccessGuardTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private MaterialLookupApi materialLookupApi;

	@Mock
	private EnrollmentAccessApi enrollmentAccessApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	private VideoAccessGuard guard;

	@BeforeEach
	void setUp() {
		guard = new VideoAccessGuard(materialLookupApi, enrollmentAccessApi, permissionCheckService);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void owningTeacherIsAllowed() {
		UUID teacherId = UUID.randomUUID();
		UUID videoAssetId = UUID.randomUUID();
		MaterialVideoOwnership ownership = new MaterialVideoOwnership(UUID.randomUUID(), UUID.randomUUID(),
				teacherId, true);
		when(materialLookupApi.resolveVideoAssetOwnership(videoAssetId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(teacherId, TENANT_ID, "TEACHER", UUID.randomUUID()));

		assertThatCode(() -> guard.requireEntitlement(videoAssetId, PermissionAction.VIEW))
			.doesNotThrowAnyException();
		verifyNoInteractions(permissionCheckService, enrollmentAccessApi);
	}

	@Test
	void nonOwningTeacherIsDenied() {
		UUID videoAssetId = UUID.randomUUID();
		MaterialVideoOwnership ownership = new MaterialVideoOwnership(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), true);
		when(materialLookupApi.resolveVideoAssetOwnership(videoAssetId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));

		assertThatThrownBy(() -> guard.requireEntitlement(videoAssetId, PermissionAction.VIEW))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void enrolledStudentIsAllowedToView() {
		UUID studentId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		UUID videoAssetId = UUID.randomUUID();
		MaterialVideoOwnership ownership = new MaterialVideoOwnership(UUID.randomUUID(), courseId,
				UUID.randomUUID(), true);
		when(materialLookupApi.resolveVideoAssetOwnership(videoAssetId)).thenReturn(Optional.of(ownership));
		when(enrollmentAccessApi.resolveAccessState(studentId, courseId))
			.thenReturn(EnrollmentAccessState.active(UUID.randomUUID(), null));
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(studentId, TENANT_ID, "STUDENT", UUID.randomUUID()));

		assertThatCode(() -> guard.requireEntitlement(videoAssetId, PermissionAction.VIEW))
			.doesNotThrowAnyException();
		verifyNoInteractions(permissionCheckService);
	}

	@Test
	void unenrolledStudentIsDeniedWithNotFound() {
		UUID studentId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		UUID videoAssetId = UUID.randomUUID();
		MaterialVideoOwnership ownership = new MaterialVideoOwnership(UUID.randomUUID(), courseId,
				UUID.randomUUID(), true);
		when(materialLookupApi.resolveVideoAssetOwnership(videoAssetId)).thenReturn(Optional.of(ownership));
		when(enrollmentAccessApi.resolveAccessState(studentId, courseId))
			.thenReturn(EnrollmentAccessState.neverEnrolled());
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(studentId, TENANT_ID, "STUDENT", UUID.randomUUID()));

		assertThatThrownBy(() -> guard.requireEntitlement(videoAssetId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
	}

	@Test
	void studentWithNonViewActionIsDeniedWithNotFoundEvenWhenEnrolled() {
		UUID studentId = UUID.randomUUID();
		UUID videoAssetId = UUID.randomUUID();
		MaterialVideoOwnership ownership = new MaterialVideoOwnership(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), true);
		when(materialLookupApi.resolveVideoAssetOwnership(videoAssetId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(studentId, TENANT_ID, "STUDENT", UUID.randomUUID()));

		assertThatThrownBy(() -> guard.requireEntitlement(videoAssetId, PermissionAction.CREATE_EDIT))
			.isInstanceOf(NotFoundException.class);
		verifyNoInteractions(enrollmentAccessApi, permissionCheckService);
	}

	@Test
	void aVideoAssetIdThatDoesNotResolveThrowsNotFoundForEveryRoleIncludingStaffAndTeacher() {
		UUID videoAssetId = UUID.randomUUID();
		when(materialLookupApi.resolveVideoAssetOwnership(videoAssetId)).thenReturn(Optional.empty());

		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "TEACHER", UUID.randomUUID()));
		assertThatThrownBy(() -> guard.requireEntitlement(videoAssetId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
		AuthenticatedPrincipalHolder.clear();

		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));
		assertThatThrownBy(() -> guard.requireEntitlement(videoAssetId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
		AuthenticatedPrincipalHolder.clear();

		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "CONTENT_MANAGER", UUID.randomUUID()));
		assertThatThrownBy(() -> guard.requireEntitlement(videoAssetId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);

		verifyNoInteractions(permissionCheckService, enrollmentAccessApi);
	}

	@Test
	void staffRoleWithoutTheMaterialsGrantIsDenied() {
		UUID videoAssetId = UUID.randomUUID();
		MaterialVideoOwnership ownership = new MaterialVideoOwnership(UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), true);
		when(materialLookupApi.resolveVideoAssetOwnership(videoAssetId)).thenReturn(Optional.of(ownership));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "CONTENT_MANAGER", UUID.randomUUID()));
		doThrow(new AccessDeniedException("denied")).when(permissionCheckService)
			.requirePermission(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT);

		assertThatThrownBy(() -> guard.requireEntitlement(videoAssetId, PermissionAction.CREATE_EDIT))
			.isInstanceOf(AccessDeniedException.class);
	}

	@Test
	void crossTenantVideoAssetIdResolvesToEmptyAndThrowsNotFound() {
		// MaterialLookupApi's own tenant-scoped resolution already ensures a
		// cross-tenant videoAssetId resolves to Optional.empty() (never a
		// cross-tenant row) - this test documents that VideoAccessGuard
		// treats that identically to "does not exist" for every role,
		// mirrored at the guard-unit level (the real cross-tenant behavior
		// is exercised end-to-end by the Testcontainers integration test).
		UUID videoAssetId = UUID.randomUUID();
		when(materialLookupApi.resolveVideoAssetOwnership(videoAssetId)).thenReturn(Optional.empty());
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), TENANT_ID, "STUDENT", UUID.randomUUID()));

		assertThatThrownBy(() -> guard.requireEntitlement(videoAssetId, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
	}

}
