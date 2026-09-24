package com.lms.contentmanagement.material.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.MaterialDownloadLimitReachedException;
import com.lms.common.error.MaterialExpiredException;
import com.lms.common.error.MaterialNotYetAvailableException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.MaterialDeletedEvent;
import com.lms.contentmanagement.material.domain.Material;
import com.lms.contentmanagement.material.domain.MaterialType;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import com.lms.contentmanagement.material.repository.MaterialRepository;
import com.lms.coursemanagement.api.LessonOwnership;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import com.lms.videoaccessmanagement.api.VideoAccessApi;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * Material CRUD + upload/delete for content-management (MVP-009, extended
 * Wave 5 with {@link MaterialType}-discriminated creation and the
 * download-limit/availability-window enforcement on {@link
 * #getDownloadUrl}). Every public method re-runs {@link
 * MaterialAccessGuard#requireLessonAccess} - defense in depth, mirroring
 * {@code CourseModuleService}'s discipline.
 *
 * <p><b>Transaction-boundary design (plan §9, {@code
 * .claude/rules/backend.md}'s "never span a transaction across an outbound
 * call" rule):</b> {@link #createMaterial} and {@link #getDownloadUrl}
 * deliberately carry NO {@code @Transactional} annotation - each may call
 * {@link ObjectStorageApi}, an outbound dependency, and each individual
 * repository call inside them is already self-transactional (Spring Data's
 * default per-method transaction) - this includes the atomic guarded {@code
 * download_count} increment ({@link
 * MaterialRepository#incrementDownloadCountIfUnderLimit}) used by {@link
 * #getDownloadUrl}. {@link #updateMaterial} and {@link #deleteMaterial}
 * carry {@code @Transactional} because they only touch the database (no
 * outbound call) and need one persistence context spanning a read + a
 * later mutation/flush.
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

	private final VideoAccessApi videoAccessApi;

	private final ApplicationEventPublisher eventPublisher;

	private final TenantContext tenantContext;

	private final long maxFileSizeBytes;

	public MaterialService(MaterialRepository materialRepository, MaterialAccessGuard materialAccessGuard,
			ObjectStorageApi objectStorageApi, VideoAccessApi videoAccessApi, ApplicationEventPublisher eventPublisher,
			TenantContext tenantContext, @Value("${app.content.material.max-file-size-bytes}") long maxFileSizeBytes) {
		this.materialRepository = materialRepository;
		this.materialAccessGuard = materialAccessGuard;
		this.objectStorageApi = objectStorageApi;
		this.videoAccessApi = videoAccessApi;
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

	/**
	 * Enforces, in order (Wave 5, plan §4): (1) the availability window
	 * ({@code availableFromAt}/{@code expiryAt}, 403 with a machine-readable
	 * code - NOT the anti-enumeration 404 shape, since the caller already
	 * legitimately sees this material listed); (2) for {@code LINK}
	 * materials, returns the raw {@code externalUrl} directly, skipping
	 * {@link ObjectStorageApi} entirely (plan §10 item 2 - no server-side
	 * control over a third-party player); (3) for every other type, the
	 * atomic guarded {@code download_count} increment, 403 {@code
	 * DOWNLOAD_LIMIT_REACHED} on a 0-row update.
	 *
	 * <p>{@code NOTE} materials have no download action at all - their
	 * content is {@code noteContent}, already returned inline by {@link
	 * #getMaterial}/{@link #listMaterials} - and {@code VIDEO}/{@code
	 * RECORDING} materials are (once {@code video-access-management} exists)
	 * served through that domain's own playback-session endpoint, never this
	 * generic signed-download-URL path. Both therefore throw {@link
	 * NotFoundException} here: "this action doesn't apply to this resource
	 * type" is modeled the same way {@code MaterialAccessGuard}/{@link
	 * #loadMaterial} already model "hidden material" for a Student - a plain
	 * 404 for an action that structurally cannot apply to this id, consistent
	 * with this module's existing convention rather than inventing a new
	 * response shape.
	 */
	public SignedDownloadUrl getDownloadUrl(UUID courseId, UUID moduleId, UUID lessonId, UUID materialId) {
		materialAccessGuard.requireLessonAccess(courseId, moduleId, lessonId, PermissionAction.VIEW);
		Material material = loadMaterial(lessonId, materialId, materialAccessGuard.isStudent());

		if (material.getMaterialType() == MaterialType.NOTE || material.getMaterialType() == MaterialType.VIDEO
				|| material.getMaterialType() == MaterialType.RECORDING) {
			throw new NotFoundException("Material not found");
		}

		Instant now = Instant.now();
		if (material.getAvailableFromAt() != null && material.getAvailableFromAt().isAfter(now)) {
			throw new MaterialNotYetAvailableException("This material is not yet available");
		}
		if (material.getExpiryAt() != null && material.getExpiryAt().isBefore(now)) {
			throw new MaterialExpiredException("This material's availability window has expired");
		}

		if (material.getMaterialType() == MaterialType.LINK) {
			return new SignedDownloadUrl(material.getExternalUrl(), null);
		}

		UUID tenantId = tenantContext.getTenantId();
		int updated = materialRepository.incrementDownloadCountIfUnderLimit(materialId, tenantId);
		if (updated == 0) {
			throw new MaterialDownloadLimitReachedException("This material's download limit has been reached");
		}
		return objectStorageApi.generateSignedDownloadUrl(material.getStorageObjectKey(), DOWNLOAD_URL_TTL);
	}

	/**
	 * Creates a {@code material} row per {@code command.materialType()}'s
	 * required-field rules (Wave 5, plan §3/§4) - see {@code MaterialType}'s
	 * own javadoc. {@code command.materialType()} defaults to {@link
	 * MaterialType#OTHER} when {@code null} (mirrors V50's own honest
	 * backfill default for pre-Wave-5 rows), so every pre-existing caller
	 * that only ever supplied a title+file keeps working unchanged.
	 */
	public MaterialView createMaterial(MaterialCreateCommand command) {
		materialAccessGuard.requireLessonAccess(command.courseId(), command.moduleId(), command.lessonId(),
				PermissionAction.CREATE_EDIT);

		MaterialType materialType = command.materialType() == null ? MaterialType.OTHER : command.materialType();
		validateFieldsForType(materialType, command);

		UUID tenantId = tenantContext.getTenantId();
		int nextSequence = materialRepository.findMaxSequenceByLessonId(command.lessonId()) + 1;

		// AuthenticatedPrincipalHolder is deliberately NOT read here, before the
		// switch below - it is read separately, per branch, only once every
		// failure-prone step for that branch (field validation for LINK/NOTE/
		// VIDEO/RECORDING; bounded read + content-sniff + object-storage store
		// for an uploaded file) has already succeeded, mirroring this method's
		// pre-Wave-5 shape exactly (finding surfaced by this wave's own
		// MaterialServiceTest coverage - reading it any earlier makes every
		// size/content-sniff-rejection unit test that never sets an
		// AuthenticatedPrincipal fail with an unrelated IllegalStateException
		// instead of the expected validation exception).
		Material material;
		switch (materialType) {
			case LINK -> material = Material.link(tenantId, command.lessonId(), command.sessionId(),
					command.title(), command.externalUrl(), nextSequence, command.maxDownloads(),
					command.availableFromAt(), command.expiryAt(), currentUserId());
			case NOTE -> material = Material.note(tenantId, command.lessonId(), command.sessionId(),
					command.title(), command.noteContent(), nextSequence, command.maxDownloads(),
					command.availableFromAt(), command.expiryAt(), currentUserId());
			case VIDEO, RECORDING -> {
				// Wave 5 follow-up (plan §4): verify videoAssetId resolves to a
				// VideoAsset that is READY and owned by this same tenant, via
				// the ONLY interface content-management may depend on for
				// video-access-management (never that domain's repository/
				// entity classes directly, per .claude/rules/architecture.md).
				// validateFieldsForType above only checked that a videoAssetId
				// was supplied at all - this is the real cross-tenant/status
				// check.
				if (!videoAccessApi.isVideoAssetReadyAndOwnedByTenant(command.videoAssetId(), tenantId)) {
					throw new FieldValidationException("Invalid video asset for this material",
							List.of(new FieldError("videoAssetId",
									"videoAssetId must reference a READY video asset owned by this tenant")));
				}
				material = Material.video(tenantId, command.lessonId(), command.sessionId(), materialType,
						command.title(), command.videoAssetId(), nextSequence, command.maxDownloads(),
						command.availableFromAt(), command.expiryAt(), currentUserId());
			}
			default -> material = createUploadedFileMaterial(tenantId, command, materialType, nextSequence);
		}

		material = materialRepository.save(material);
		return toView(material);
	}

	private static UUID currentUserId() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

	private Material createUploadedFileMaterial(UUID tenantId, MaterialCreateCommand command,
			MaterialType materialType, int nextSequence) {
		// Bounded streaming read (finding 1, MVP-009 review): aborts as soon as
		// the running byte count exceeds maxFileSizeBytes, so an oversized
		// upload is rejected without ever buffering the full file in memory -
		// unlike a file.getBytes() call, which would fully materialize the
		// upload BEFORE the size check ran, bounded only by the
		// independently-maintained spring.servlet.multipart.max-file-size
		// container setting. The zero-length check still runs after the read
		// completes, since an empty file never crosses the size ceiling on its
		// own.
		byte[] bytes = readBoundedBytes(command.file(), maxFileSizeBytes);
		if (bytes.length == 0) {
			throw new PayloadTooLargeException("The uploaded file exceeds the maximum allowed size");
		}
		String sniffedMimeType = ContentSniffer.sniff(bytes);
		if (sniffedMimeType == null) {
			throw new UnsupportedMediaTypeException(
					"The uploaded file's content does not match an accepted format (PDF, image, or plain text notes)");
		}

		String originalFilename = sanitizeFilename(command.file().getOriginalFilename());
		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId,
				new ByteArrayInputStream(bytes), sniffedMimeType, bytes.length, originalFilename));

		return Material.uploadedFile(tenantId, command.lessonId(), command.sessionId(), materialType, command.title(),
				originalFilename, stored.objectKey(), sniffedMimeType, stored.sizeBytes(), nextSequence,
				command.maxDownloads(), command.availableFromAt(), command.expiryAt(), currentUserId());
	}

	/**
	 * Per-{@link MaterialType} required-field validation (Wave 5, plan §3/§4)
	 * - a clean {@link FieldValidationException}/400 instead of a raw
	 * DB-constraint-violation 500 for the same rule V51's {@code
	 * ck_material_*_required} CHECK constraints already enforce as the real
	 * backstop.
	 */
	private static void validateFieldsForType(MaterialType materialType, MaterialCreateCommand command) {
		List<FieldError> errors = new ArrayList<>();
		switch (materialType) {
			case LINK -> {
				if (isBlank(command.externalUrl())) {
					errors.add(new FieldError("externalUrl", "externalUrl is required for LINK materials"));
				}
				else if (!isPlausibleUrl(command.externalUrl())) {
					errors.add(new FieldError("externalUrl", "externalUrl must be a valid absolute URL"));
				}
			}
			case NOTE -> {
				if (isBlank(command.noteContent())) {
					errors.add(new FieldError("noteContent", "noteContent is required for NOTE materials"));
				}
			}
			case VIDEO, RECORDING -> {
				if (command.videoAssetId() == null) {
					errors.add(
							new FieldError("videoAssetId", "videoAssetId is required for VIDEO/RECORDING materials"));
				}
			}
			default -> {
				if (command.file() == null || command.file().isEmpty()) {
					errors.add(new FieldError("file", "A file upload is required for this material type"));
				}
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid material fields for the selected material type", errors);
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	/**
	 * Deliberately minimal (plan's own instruction): non-blank plus a
	 * syntactic {@link URI} parse check requiring an absolute URL with a
	 * host - enough to reject garbage input without this module owning a
	 * real URL-validation library. Never dereferences the URL (no outbound
	 * call here) - this is shape validation only, not a reachability check.
	 */
	private static boolean isPlausibleUrl(String value) {
		try {
			URI uri = new URI(value);
			return uri.isAbsolute() && uri.getHost() != null && !uri.getHost().isBlank();
		}
		catch (URISyntaxException e) {
			return false;
		}
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
		return new MaterialView(material.getId(), material.getLessonId(), material.getSessionId(),
				material.getMaterialType(), material.getTitle(), material.getOriginalFilename(),
				material.getMimeType(), material.getSizeBytes(), material.getExternalUrl(),
				material.getNoteContent(), material.getVideoAssetId(), material.getSequence(),
				material.getVisibility(), material.getMaxDownloads(), material.getDownloadCount(),
				material.getAvailableFromAt(), material.getExpiryAt(), material.getUploadedBy(),
				material.getCreatedAt(), material.getUpdatedAt());
	}

}
