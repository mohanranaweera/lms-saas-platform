package com.lms.contentmanagement.material.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.MaterialDeletedEvent;
import com.lms.contentmanagement.material.domain.Material;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import com.lms.contentmanagement.material.repository.MaterialRepository;
import com.lms.contentmanagement.material.storage.ObjectStorageApi;
import com.lms.contentmanagement.material.storage.StoreObjectCommand;
import com.lms.contentmanagement.material.storage.StoredObject;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Mockito-only unit coverage for {@link MaterialService} (MVP-009), matching
 * {@code CourseServiceTest}/{@code CourseModuleServiceTest}'s style. Isolates
 * this service's own branching logic - size/content-sniffing gates, sequence
 * computation, Student visibility filtering, sequence-conflict handling, and
 * the delete+audit-event transaction boundary - without a Spring context; the
 * real HTTP-layer equivalent lives in the {@code content-management}
 * Testcontainers integration tests.
 */
@ExtendWith(MockitoExtension.class)
class MaterialServiceTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final long MAX_FILE_SIZE_BYTES = 1_000_000L;

	@Mock
	private MaterialRepository materialRepository;

	@Mock
	private MaterialAccessGuard materialAccessGuard;

	@Mock
	private ObjectStorageApi objectStorageApi;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	@Mock
	private TenantContext tenantContext;

	private MaterialService materialService;

	@BeforeEach
	void setUp() {
		materialService = new MaterialService(materialRepository, materialAccessGuard, objectStorageApi,
				eventPublisher, tenantContext, MAX_FILE_SIZE_BYTES);
	}

	@AfterEach
	void clearPrincipal() {
		AuthenticatedPrincipalHolder.clear();
	}

	// ------------------------------------------------------------------
	// createMaterial.
	// ------------------------------------------------------------------

	@Test
	void createMaterialWithAValidSmallPdfSucceedsAndComputesTheNextSequence() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(materialRepository.findMaxSequenceByLessonId(lessonId)).thenReturn(2);
		when(objectStorageApi.store(any(StoreObjectCommand.class)))
			.thenReturn(new StoredObject("object-key-123", 123L));
		when(materialRepository.save(any(Material.class))).thenAnswer(invocation -> invocation.getArgument(0));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(actorId, TENANT_ID, "TEACHER", UUID.randomUUID()));
		MockMultipartFile file = new MockMultipartFile("file", "lecture-1.pdf", "application/pdf", validPdfBytes());

		MaterialView view = materialService.createMaterial(courseId, moduleId, lessonId, "Lecture 1", file);

		assertThat(view.mimeType()).isEqualTo("application/pdf");
		assertThat(view.sequence()).isEqualTo(3);
		assertThat(view.title()).isEqualTo("Lecture 1");
		verify(materialAccessGuard).requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.CREATE_EDIT);
		verify(objectStorageApi, times(1)).store(any(StoreObjectCommand.class));
		verify(objectStorageApi, never()).delete(any());
	}

	@Test
	void createMaterialWithAFileExceedingTheMaxSizeThrowsPayloadTooLargeAndNeverCallsStore() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		byte[] oversized = new byte[(int) MAX_FILE_SIZE_BYTES + 1];
		System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, oversized, 0, 5);
		MockMultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", oversized);

		assertThatThrownBy(() -> materialService.createMaterial(courseId, moduleId, lessonId, "Big File", file))
			.isInstanceOf(PayloadTooLargeException.class);

		verifyNoInteractions(objectStorageApi);
		verify(materialRepository, never()).save(any());
	}

	/**
	 * Finding 1 (MVP-009 review): the size-limit rejection path must abort as
	 * soon as the running byte count crosses {@code maxFileSizeBytes},
	 * WITHOUT first reading (and therefore buffering) the rest of an
	 * oversized file - unlike the previous {@code file.getBytes()}-based
	 * implementation, which fully materialized the upload before the size
	 * check ever ran. {@link BoundedRejectingInputStream} only ever hands out
	 * a small, generous slack margin of bytes past {@code
	 * MAX_FILE_SIZE_BYTES} before throwing; if {@code MaterialService} still
	 * tried to read the "whole file" first, it would keep asking this stream
	 * for more bytes past that slack margin, and this test would fail with
	 * the stream's own {@link AssertionError} instead of the expected {@link
	 * PayloadTooLargeException}.
	 */
	@Test
	void createMaterialWithAFileExceedingTheMaxSizeNeverBuffersTheFullOversizedFileBeforeRejecting() throws Exception {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		long allowedBeforeExplosion = MAX_FILE_SIZE_BYTES + 65_536L;
		MultipartFile file = mock(MultipartFile.class);
		when(file.getInputStream()).thenReturn(new BoundedRejectingInputStream(allowedBeforeExplosion));

		assertThatThrownBy(() -> materialService.createMaterial(courseId, moduleId, lessonId, "Huge File", file))
			.isInstanceOf(PayloadTooLargeException.class);

		verifyNoInteractions(objectStorageApi);
		verify(materialRepository, never()).save(any());
	}

	@Test
	void createMaterialWithBytesFailingContentSnifferThrowsUnsupportedMediaTypeAndNeverCallsStore() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		byte[] mzHeader = { 0x4D, 0x5A, (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00 };
		MockMultipartFile file = new MockMultipartFile("file", "disguised.pdf", "application/pdf", mzHeader);

		assertThatThrownBy(
				() -> materialService.createMaterial(courseId, moduleId, lessonId, "Disguised", file))
			.isInstanceOf(UnsupportedMediaTypeException.class);

		verifyNoInteractions(objectStorageApi);
		verify(materialRepository, never()).save(any());
	}

	@Test
	void createMaterialWhenObjectStorageStoreThrowsPropagatesAndNeverSavesAMaterialRow() {
		// M3 - a storage failure AFTER validation (size/content-sniff) passes
		// must not orphan a material row: the row must only ever be saved
		// once the object genuinely landed in storage.
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		when(tenantContext.getTenantId()).thenReturn(TENANT_ID);
		when(objectStorageApi.store(any(StoreObjectCommand.class))).thenThrow(new RuntimeException("storage unavailable"));
		MockMultipartFile file = new MockMultipartFile("file", "lecture-1.pdf", "application/pdf", validPdfBytes());

		assertThatThrownBy(() -> materialService.createMaterial(courseId, moduleId, lessonId, "Lecture 1", file))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("storage unavailable");

		verify(materialRepository, never()).save(any());
	}

	@Test
	void createMaterialWhenTheGuardDeniesAccessPropagatesAndNeverCallsStoreOrSave() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		when(materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.CREATE_EDIT))
			.thenThrow(new AccessDeniedException("denied"));
		MockMultipartFile file = new MockMultipartFile("file", "notes.pdf", "application/pdf", validPdfBytes());

		assertThatThrownBy(() -> materialService.createMaterial(courseId, moduleId, lessonId, "Notes", file))
			.isInstanceOf(AccessDeniedException.class);

		verifyNoInteractions(objectStorageApi);
		verify(materialRepository, never()).save(any());
	}

	// ------------------------------------------------------------------
	// listMaterials / getMaterial visibility filtering.
	// ------------------------------------------------------------------

	@Test
	void listMaterialsFiltersOutHiddenMaterialsForAStudent() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		Material visible = materialFixture(lessonId, MaterialVisibility.VISIBLE);
		Material hidden = materialFixture(lessonId, MaterialVisibility.HIDDEN);
		when(materialRepository.findByLessonId(lessonId)).thenReturn(List.of(visible, hidden));
		when(materialAccessGuard.isStudent()).thenReturn(true);

		List<MaterialView> views = materialService.listMaterials(courseId, moduleId, lessonId);

		assertThat(views).extracting(MaterialView::title).containsExactly(visible.getTitle());
		verify(materialAccessGuard).requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW);
	}

	@Test
	void listMaterialsReturnsBothVisibleAndHiddenForANonStudent() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		Material visible = materialFixture(lessonId, MaterialVisibility.VISIBLE);
		Material hidden = materialFixture(lessonId, MaterialVisibility.HIDDEN);
		when(materialRepository.findByLessonId(lessonId)).thenReturn(List.of(visible, hidden));
		when(materialAccessGuard.isStudent()).thenReturn(false);

		List<MaterialView> views = materialService.listMaterials(courseId, moduleId, lessonId);

		assertThat(views).hasSize(2);
	}

	@Test
	void getMaterialForAStudentOnAHiddenMaterialThrowsNotFound() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		Material hidden = materialFixture(lessonId, MaterialVisibility.HIDDEN);
		when(materialRepository.findByIdAndLessonId(materialId, lessonId)).thenReturn(Optional.of(hidden));
		when(materialAccessGuard.isStudent()).thenReturn(true);

		assertThatThrownBy(() -> materialService.getMaterial(courseId, moduleId, lessonId, materialId))
			.isInstanceOf(NotFoundException.class);
	}

	// ------------------------------------------------------------------
	// updateMaterial sequence-conflict handling.
	// ------------------------------------------------------------------

	@Test
	void updateMaterialThrowsConflictWhenTheNewSequenceCollidesWithADifferentMaterial() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		Material material = materialFixture(lessonId, MaterialVisibility.VISIBLE);
		when(materialRepository.findByIdAndLessonId(materialId, lessonId)).thenReturn(Optional.of(material));
		when(materialRepository.existsByLessonIdAndSequenceAndIdNot(lessonId, 2, materialId)).thenReturn(true);

		assertThatThrownBy(() -> materialService.updateMaterial(courseId, moduleId, lessonId, materialId,
				"Renamed", 2, MaterialVisibility.VISIBLE))
			.isInstanceOf(ConflictException.class);
	}

	@Test
	void updateMaterialWhenTheGuardDeniesAccessNeverPersistsAChange() {
		// Finding 5 (MVP-009 review) - mirrors the existing create/delete
		// "guard denies access" tests; updateMaterial had no equivalent unit
		// coverage even though it calls the guard first, same as every other
		// mutating method.
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		when(materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.CREATE_EDIT))
			.thenThrow(new AccessDeniedException("denied"));

		assertThatThrownBy(() -> materialService.updateMaterial(courseId, moduleId, lessonId, materialId, "Renamed",
				1, MaterialVisibility.VISIBLE))
			.isInstanceOf(AccessDeniedException.class);

		verify(materialRepository, never()).findByIdAndLessonId(any(), any());
		verify(materialRepository, never()).existsByLessonIdAndSequenceAndIdNot(any(), any(), any());
	}

	@Test
	void updateMaterialKeepingTheSameSequenceNeverChecksForACollision() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		Material material = materialFixture(lessonId, MaterialVisibility.VISIBLE);
		when(materialRepository.findByIdAndLessonId(materialId, lessonId)).thenReturn(Optional.of(material));

		MaterialView view = materialService.updateMaterial(courseId, moduleId, lessonId, materialId, "Renamed", 1,
				MaterialVisibility.VISIBLE);

		assertThat(view.title()).isEqualTo("Renamed");
		verify(materialRepository, never()).existsByLessonIdAndSequenceAndIdNot(any(), any(), any());
	}

	// ------------------------------------------------------------------
	// deleteMaterial + MaterialDeletedEvent.
	// ------------------------------------------------------------------

	@Test
	void deleteMaterialDeletesTheRowAndPublishesExactlyOneEventWithTheCorrectFields() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		UUID actorId = UUID.randomUUID();
		UUID teacherId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, teacherId, true);
		when(materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.DELETE))
			.thenReturn(ownership);
		Material material = materialFixture(lessonId, MaterialVisibility.VISIBLE);
		ReflectionTestUtils.setField(material, "id", materialId);
		when(materialRepository.findByIdAndLessonId(materialId, lessonId)).thenReturn(Optional.of(material));
		AuthenticatedPrincipalHolder
			.set(new AuthenticatedPrincipal(actorId, TENANT_ID, "TENANT_ADMIN", UUID.randomUUID()));

		materialService.deleteMaterial(courseId, moduleId, lessonId, materialId);

		verify(materialRepository, times(1)).delete(material);
		ArgumentCaptor<MaterialDeletedEvent> captor = ArgumentCaptor.forClass(MaterialDeletedEvent.class);
		verify(eventPublisher, times(1)).publishEvent(captor.capture());
		MaterialDeletedEvent event = captor.getValue();
		assertThat(event.tenantId()).isEqualTo(material.getTenantId());
		assertThat(event.materialId()).isEqualTo(materialId);
		assertThat(event.lessonId()).isEqualTo(lessonId);
		assertThat(event.moduleId()).isEqualTo(moduleId);
		assertThat(event.courseId()).isEqualTo(courseId);
		assertThat(event.title()).isEqualTo(material.getTitle());
		assertThat(event.mimeType()).isEqualTo(material.getMimeType());
		assertThat(event.storageObjectKey()).isEqualTo(material.getStorageObjectKey());
		assertThat(event.uploadedBy()).isEqualTo(material.getUploadedBy());
		assertThat(event.deletedBy()).isEqualTo(actorId);
	}

	@Test
	void deleteMaterialForANonexistentOrAlreadyDeletedMaterialIdThrowsNotFoundAndNeverPublishes() {
		// M4 - access guard succeeds (caller is a legitimate owning
		// Teacher/staff), but the material row itself is gone (nonexistent id,
		// or a concurrent second delete of the same id) - must surface as
		// NotFoundException, and must never publish a MaterialDeletedEvent for
		// a row that was never actually deleted.
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		UUID teacherId = UUID.randomUUID();
		LessonOwnership ownership = new LessonOwnership(lessonId, moduleId, courseId, teacherId, true);
		when(materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.DELETE))
			.thenReturn(ownership);
		when(materialRepository.findByIdAndLessonId(materialId, lessonId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> materialService.deleteMaterial(courseId, moduleId, lessonId, materialId))
			.isInstanceOf(NotFoundException.class);

		verify(materialRepository, never()).delete(any(Material.class));
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void deleteMaterialWhenTheGuardDeniesAccessNeverDeletesOrPublishes() {
		UUID courseId = UUID.randomUUID();
		UUID moduleId = UUID.randomUUID();
		UUID lessonId = UUID.randomUUID();
		UUID materialId = UUID.randomUUID();
		when(materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.DELETE))
			.thenThrow(new AccessDeniedException("denied"));

		assertThatThrownBy(() -> materialService.deleteMaterial(courseId, moduleId, lessonId, materialId))
			.isInstanceOf(AccessDeniedException.class);

		verify(materialRepository, never()).delete(any(Material.class));
		verifyNoInteractions(eventPublisher);
	}

	// ------------------------------------------------------------------
	// onMaterialDeleted (@TransactionalEventListener).
	// ------------------------------------------------------------------

	@Test
	void onMaterialDeletedCallsObjectStorageDeleteWithTheEventsStorageObjectKey() {
		MaterialDeletedEvent event = materialDeletedEventFixture("storage-key-xyz");

		materialService.onMaterialDeleted(event);

		verify(objectStorageApi, times(1)).delete("storage-key-xyz");
	}

	@Test
	void onMaterialDeletedDoesNotRethrowWhenObjectStorageDeleteThrows() {
		MaterialDeletedEvent event = materialDeletedEventFixture("storage-key-xyz");
		doThrow(new RuntimeException("object storage unavailable")).when(objectStorageApi).delete("storage-key-xyz");

		assertThatCode(() -> materialService.onMaterialDeleted(event)).doesNotThrowAnyException();
	}

	// ------------------------------------------------------------------
	// Fixtures.
	// ------------------------------------------------------------------

	private static Material materialFixture(UUID lessonId, MaterialVisibility visibility) {
		Material material = new Material(TENANT_ID, lessonId, "Material " + UUID.randomUUID(), "file.pdf",
				"storage-key-" + UUID.randomUUID(), "application/pdf", 1024L, 1, UUID.randomUUID());
		material.setVisibility(visibility);
		return material;
	}

	private static MaterialDeletedEvent materialDeletedEventFixture(String storageObjectKey) {
		return new MaterialDeletedEvent(TENANT_ID, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				UUID.randomUUID(), "Deleted Material", "application/pdf", storageObjectKey, UUID.randomUUID(),
				UUID.randomUUID(), Instant.now());
	}

	private static byte[] validPdfBytes() {
		return "%PDF-1.4\n%Fixture PDF content for MVP-009 tests.\n%%EOF".getBytes(StandardCharsets.US_ASCII);
	}

	/**
	 * An effectively-infinite (never returns EOF) fake upload stream that
	 * throws {@link AssertionError} once more than {@code
	 * maxBytesBeforeFailure} bytes have been served across all calls -
	 * simulates an oversized upload for finding 1's "must never fully buffer
	 * an oversized file before rejecting it" test without allocating a real
	 * huge byte array.
	 */
	private static final class BoundedRejectingInputStream extends InputStream {

		private final long maxBytesBeforeFailure;

		private long bytesServed;

		private BoundedRejectingInputStream(long maxBytesBeforeFailure) {
			this.maxBytesBeforeFailure = maxBytesBeforeFailure;
		}

		@Override
		public int read() {
			byte[] single = new byte[1];
			int read = read(single, 0, 1);
			return read == -1 ? -1 : (single[0] & 0xFF);
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (bytesServed >= maxBytesBeforeFailure) {
				throw new AssertionError(
						"MaterialService read past the declared size-limit slack margin before rejecting the "
								+ "upload - it must abort as soon as the running byte count exceeds the configured "
								+ "max, never buffer the rest of an oversized file first.");
			}
			int toServe = (int) Math.min(len, maxBytesBeforeFailure - bytesServed);
			Arrays.fill(b, off, off + toServe, (byte) 'A');
			bytesServed += toServe;
			return toServe;
		}

	}

}
