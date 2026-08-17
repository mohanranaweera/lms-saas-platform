package com.lms.contentmanagement.material.service;

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
import com.lms.contentmanagement.material.storage.SignedDownloadUrl;
import com.lms.contentmanagement.material.storage.StoreObjectCommand;
import com.lms.contentmanagement.material.storage.StoredObject;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.multipart.MultipartFile;

/**
 * Material CRUD + upload/delete for content-management (MVP-009). Every
 * public method re-runs {@link MaterialAccessGuard#requireLessonAccess} -
 * defense in depth, mirroring {@code CourseModuleService}'s discipline.
 *
 * <p><b>Transaction-boundary design (plan §9, {@code
 * .claude/rules/backend.md}'s "never span a transaction across an outbound
 * call" rule):</b> {@link #createMaterial} and {@link #getDownloadUrl}
 * deliberately carry NO {@code @Transactional} annotation - each calls
 * {@link ObjectStorageApi}, an outbound dependency, and each individual
 * repository call inside them is already self-transactional (Spring Data's
 * default per-method transaction). {@link #updateMaterial} and {@link
 * #deleteMaterial} carry {@code @Transactional} because they only touch the
 * database (no outbound call) and need one persistence context spanning a
 * read + a later mutation/flush.
 */
@Service
public class MaterialService {

	private static final Logger log = LoggerFactory.getLogger(MaterialService.class);

	private static final Duration DOWNLOAD_URL_TTL = Duration.ofMinutes(5);

	// Chunk size for the bounded streaming read in readBoundedBytes - not
	// itself the security boundary (maxFileSizeBytes is), just an I/O buffer
	// size (finding 1, MVP-009 review).
	private static final int STREAM_READ_CHUNK_SIZE = 8192;

	private final MaterialRepository materialRepository;

	private final MaterialAccessGuard materialAccessGuard;

	private final ObjectStorageApi objectStorageApi;

	private final ApplicationEventPublisher eventPublisher;

	private final TenantContext tenantContext;

	private final long maxFileSizeBytes;

	public MaterialService(MaterialRepository materialRepository, MaterialAccessGuard materialAccessGuard,
			ObjectStorageApi objectStorageApi, ApplicationEventPublisher eventPublisher, TenantContext tenantContext,
			@Value("${app.content.material.max-file-size-bytes}") long maxFileSizeBytes) {
		this.materialRepository = materialRepository;
		this.materialAccessGuard = materialAccessGuard;
		this.objectStorageApi = objectStorageApi;
		this.eventPublisher = eventPublisher;
		this.tenantContext = tenantContext;
		this.maxFileSizeBytes = maxFileSizeBytes;
	}

	@Transactional(readOnly = true)
	public List<MaterialView> listMaterials(UUID courseId, UUID moduleId, UUID lessonId) {
		materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW);
		List<Material> materials = materialRepository.findByLessonId(lessonId);
		if (materialAccessGuard.isStudent()) {
			materials = materials.stream().filter(m -> m.getVisibility() == MaterialVisibility.VISIBLE).toList();
		}
		return materials.stream().map(MaterialService::toView).toList();
	}

	@Transactional(readOnly = true)
	public MaterialView getMaterial(UUID courseId, UUID moduleId, UUID lessonId, UUID materialId) {
		materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW);
		return toView(loadMaterial(lessonId, materialId, materialAccessGuard.isStudent()));
	}

	public SignedDownloadUrl getDownloadUrl(UUID courseId, UUID moduleId, UUID lessonId, UUID materialId) {
		materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW);
		Material material = loadMaterial(lessonId, materialId, materialAccessGuard.isStudent());
		return objectStorageApi.generateSignedDownloadUrl(material.getStorageObjectKey(), DOWNLOAD_URL_TTL);
	}

	public MaterialView createMaterial(UUID courseId, UUID moduleId, UUID lessonId, String title,
			MultipartFile file) {
		materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.CREATE_EDIT);

		// Bounded streaming read (finding 1, MVP-009 review): aborts as soon as
		// the running byte count exceeds maxFileSizeBytes, so an oversized
		// upload is rejected without ever buffering the full file in memory -
		// unlike the previous file.getBytes() call, which fully materialized
		// the upload BEFORE the size check ran, bounded only by the
		// independently-maintained spring.servlet.multipart.max-file-size
		// container setting. The zero-length check still runs after the read
		// completes, since an empty file never crosses the size ceiling on its
		// own.
		byte[] bytes = readBoundedBytes(file, maxFileSizeBytes);
		if (bytes.length == 0) {
			throw new PayloadTooLargeException("The uploaded file exceeds the maximum allowed size");
		}
		String sniffedMimeType = ContentSniffer.sniff(bytes);
		if (sniffedMimeType == null) {
			throw new UnsupportedMediaTypeException(
					"The uploaded file's content does not match an accepted format (PDF, image, or plain text notes)");
		}

		UUID tenantId = tenantContext.getTenantId();
		String originalFilename = sanitizeFilename(file.getOriginalFilename());
		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId,
				new ByteArrayInputStream(bytes), sniffedMimeType, bytes.length, originalFilename));

		int nextSequence = materialRepository.findMaxSequenceByLessonId(lessonId) + 1;
		UUID uploadedBy = AuthenticatedPrincipalHolder.get().userId();
		Material material = new Material(tenantId, lessonId, title, originalFilename, stored.objectKey(),
				sniffedMimeType, stored.sizeBytes(), nextSequence, uploadedBy);
		material = materialRepository.save(material);
		return toView(material);
	}

	@Transactional
	public MaterialView updateMaterial(UUID courseId, UUID moduleId, UUID lessonId, UUID materialId, String title,
			Integer sequence, MaterialVisibility visibility) {
		materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.CREATE_EDIT);
		Material material = materialRepository.findByIdAndLessonId(materialId, lessonId)
			.orElseThrow(() -> new NotFoundException("Material not found"));

		if (!material.getSequence().equals(sequence)
				&& materialRepository.existsByLessonIdAndSequenceAndIdNot(lessonId, sequence, materialId)) {
			throw new ConflictException("A material with this position already exists in this lesson");
		}

		// No explicit materialRepository.save(material) call here - `material`
		// is a managed entity loaded earlier in this same @Transactional
		// method, so Hibernate's dirty-checking flushes these field mutations
		// on commit automatically (finding 7, MVP-009 review). This relies on
		// @Transactional staying on this method AND the read staying inside
		// the same transaction as the write - do not move the read outside
		// the transaction boundary or drop @Transactional without adding an
		// explicit save() back in.
		material.setTitle(title);
		material.setSequence(sequence);
		material.setVisibility(visibility);
		return toView(material);
	}

	@Transactional
	public void deleteMaterial(UUID courseId, UUID moduleId, UUID lessonId, UUID materialId) {
		LessonOwnership ownership = materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId,
				PermissionAction.DELETE);
		Material material = materialRepository.findByIdAndLessonId(materialId, lessonId)
			.orElseThrow(() -> new NotFoundException("Material not found"));

		UUID deletedBy = AuthenticatedPrincipalHolder.get().userId();
		MaterialDeletedEvent event = new MaterialDeletedEvent(material.getTenantId(), material.getId(), lessonId,
				ownership.moduleId(), ownership.courseId(), material.getTitle(), material.getMimeType(),
				material.getStorageObjectKey(), material.getUploadedBy(), deletedBy, Instant.now());

		materialRepository.delete(material);
		eventPublisher.publishEvent(event);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onMaterialDeleted(MaterialDeletedEvent event) {
		try {
			objectStorageApi.delete(event.storageObjectKey());
		}
		catch (RuntimeException e) {
			// Object storage is not wired up yet (UnavailableObjectStorageApi) or a
			// real provider call failed after the DB row was already
			// committed-deleted - log rather than propagate, since the HTTP
			// response has already been sent by this point (plan §9/§21 item 1).
			log.warn("Failed to delete storage object {} for deleted material {}", event.storageObjectKey(),
					event.materialId(), e);
		}
	}

	private Material loadMaterial(UUID lessonId, UUID materialId, boolean isStudent) {
		Material material = materialRepository.findByIdAndLessonId(materialId, lessonId)
			.orElseThrow(() -> new NotFoundException("Material not found"));
		if (isStudent && material.getVisibility() != MaterialVisibility.VISIBLE) {
			// Same anti-enumeration rule as MaterialAccessGuard - a Student must
			// never distinguish "hidden" from "nonexistent" (plan §16/§21 item 6).
			throw new NotFoundException("Material not found");
		}
		return material;
	}

	private static String sanitizeFilename(String rawFilename) {
		String name = rawFilename == null ? "upload" : rawFilename;
		// Strip any path component - only the basename should ever be stored,
		// regardless of separator style the client's OS/browser used.
		int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (lastSlash >= 0) {
			name = name.substring(lastSlash + 1);
		}
		// Strip control characters (defense-in-depth against embedded NUL/CR/LF etc.)
		name = name.chars()
			.filter(c -> c >= 0x20)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString();
		if (name.isBlank()) {
			name = "upload";
		}
		return name.length() > 255 ? name.substring(0, 255) : name;
	}

	/**
	 * Reads {@code file}'s content incrementally, aborting with {@link
	 * PayloadTooLargeException} as soon as the running byte count exceeds
	 * {@code maxFileSizeBytes} - the file is never fully buffered before the
	 * size check runs (finding 1, MVP-009 review). Never trusts {@code
	 * file.getSize()}/a client-declared {@code Content-Length} to decide
	 * whether to reject; the limit is enforced purely against bytes actually
	 * read off the stream.
	 */
	private static byte[] readBoundedBytes(MultipartFile file, long maxFileSizeBytes) {
		try (InputStream input = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[STREAM_READ_CHUNK_SIZE];
			long totalBytesRead = 0;
			int bytesRead;
			while ((bytesRead = input.read(chunk)) != -1) {
				totalBytesRead += bytesRead;
				if (totalBytesRead > maxFileSizeBytes) {
					throw new PayloadTooLargeException("The uploaded file exceeds the maximum allowed size");
				}
				buffer.write(chunk, 0, bytesRead);
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read uploaded file", e);
		}
	}

	private static MaterialView toView(Material material) {
		return new MaterialView(material.getId(), material.getLessonId(), material.getTitle(),
				material.getOriginalFilename(), material.getMimeType(), material.getSizeBytes(),
				material.getSequence(), material.getVisibility(), material.getExpiryAt(), material.getUploadedBy(),
				material.getCreatedAt(), material.getUpdatedAt());
	}

}
