package com.lms.contentmanagement.material.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.error.MediaNotReadyException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.material.domain.Material;
import com.lms.contentmanagement.material.repository.MaterialRepository;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.videoaccessmanagement.api.VideoAccessApi;
import com.lms.videoaccessmanagement.api.VideoAssetDetails;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LessonMediaServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	@Mock
	private MaterialRepository materialRepository;
	@Mock
	private MaterialAccessGuard materialAccessGuard;
	@Mock
	private VideoAccessApi videoAccessApi;
	@Mock
	private TenantContext tenantContext;
	@Mock
	private AuditLogApi auditLogApi;

	private LessonMediaService service;

	@BeforeEach
	void setUp() {
		service = new LessonMediaService(materialRepository, materialAccessGuard, videoAccessApi, tenantContext, auditLogApi);
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void attachRejectsAnAssetThatIsNotReady() {
		UUID courseId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(materialAccessGuard.requireLessonAccess(courseId, lessonId, PermissionAction.CREATE_EDIT))
				.thenReturn(new LessonOwnership(lessonId, UUID.randomUUID(), courseId, actorId, true));
		when(videoAccessApi.getDetails(assetId, TENANT_ID))
				.thenReturn(Optional.of(new VideoAssetDetails(assetId, "PROCESSING", "YOUTUBE", "Processing", null, null, null, null)));
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(actorId, TENANT_ID, "TEACHER", UUID.randomUUID()));

		assertThatThrownBy(() -> service.attach(courseId, lessonId, assetId, null, null))
				.isInstanceOf(MediaNotReadyException.class);
	}

	@Test
	void attachRejectsAnUnknownOrForeignAssetWithoutWritingMaterial() {
		UUID courseId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(materialAccessGuard.requireLessonAccess(courseId, lessonId, PermissionAction.CREATE_EDIT))
				.thenReturn(new LessonOwnership(lessonId, UUID.randomUUID(), courseId, actorId, true));
		when(videoAccessApi.getDetails(assetId, TENANT_ID)).thenReturn(Optional.empty());
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(actorId, TENANT_ID, "TEACHER", UUID.randomUUID()));

		assertThatThrownBy(() -> service.attach(courseId, lessonId, assetId, null, null))
				.isInstanceOf(com.lms.common.error.NotFoundException.class);
		org.mockito.Mockito.verifyNoInteractions(materialRepository);
	}

	@Test
	void attachRejectsASecondReferenceToTheSameAssetInTheLesson() {
		UUID courseId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID assetId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(materialAccessGuard.requireLessonAccess(courseId, lessonId, PermissionAction.CREATE_EDIT))
				.thenReturn(new LessonOwnership(lessonId, UUID.randomUUID(), courseId, actorId, true));
		when(videoAccessApi.getDetails(assetId, TENANT_ID))
				.thenReturn(Optional.of(new VideoAssetDetails(assetId, "READY", "INTERNAL", "Ready", null, null, null, null)));
		when(materialRepository.findVideoByLessonIdAndVideoAssetId(lessonId, assetId))
				.thenReturn(Optional.of(org.mockito.Mockito.mock(Material.class)));
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(actorId, TENANT_ID, "TEACHER", UUID.randomUUID()));

		assertThatThrownBy(() -> service.attach(courseId, lessonId, assetId, null, null))
				.isInstanceOf(com.lms.common.error.ConflictException.class);
	}

}
