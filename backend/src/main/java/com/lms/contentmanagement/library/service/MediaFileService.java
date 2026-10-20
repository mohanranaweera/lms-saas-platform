package com.lms.contentmanagement.library.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.library.domain.MediaFile;
import com.lms.contentmanagement.library.domain.MediaFileKind;
import com.lms.contentmanagement.library.repository.MediaFileRepository;
import com.lms.contentmanagement.library.support.MediaFileAccessGuard;
import com.lms.contentmanagement.library.support.MediaFileAccessGuard.CallerRights;
import com.lms.contentmanagement.material.repository.MaterialRepository;
import com.lms.contentmanagement.material.service.ContentSniffer;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.integrationmanagement.api.DownloadOverrides;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import com.lms.usermanagement.api.TeacherLookupApi;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * Institute media file library (Klass gaps round 2 G1; R2-D1..R2-D3; V88).
 * Documents and images are stored ONCE in external object storage and can be
 * attached to any number of lessons as library-backed materials without
 * copying bytes ({@code MaterialService}).
 *
 * <p><b>Tenant isolation</b> is structural ({@link MediaFileRepository} is
 * tenant-aware): another tenant's id is simply not found (404). <b>Authorization</b>
 * is {@link MediaFileAccessGuard} (students 403; teachers manage only their own
 * uploads; other staff by the {@code MATERIALS} matrix).
 *
 * <p><b>Transactions</b> never span an object-storage call: upload stores the
 * bytes first, then inserts + audits in one transaction (and deletes the
 * stored object again if that transaction fails); delete soft-deletes + audits
 * in one transaction and removes the stored object only after commit. Signed
 * URLs are generated outside any transaction.
 */
@Service
public class MediaFileService {

	private static final Logger log = LoggerFactory.getLogger(MediaFileService.class);

	static final int MAX_PAGE_SIZE = 100;

	private static final Duration OPEN_URL_TTL = Duration.ofMinutes(5);

	private static final int STREAM_READ_CHUNK_SIZE = 8192;

	private static final String TARGET_ENTITY = "media_file";

	private final MediaFileRepository mediaFileRepository;

	private final MaterialRepository materialRepository;

	private final MediaFileAccessGuard accessGuard;

	private final ObjectStorageApi objectStorageApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final TeacherLookupApi teacherLookupApi;

	private final UserProvisioningApi userProvisioningApi;

	private final ApplicationEventPublisher eventPublisher;

	private final EntityManager entityManager;

	private final TransactionTemplate transactionTemplate;

	private final long maxFileSizeBytes;

	public MediaFileService(MediaFileRepository mediaFileRepository, MaterialRepository materialRepository,
			MediaFileAccessGuard accessGuard, ObjectStorageApi objectStorageApi, AuditLogApi auditLogApi,
			TenantContext tenantContext, TeacherLookupApi teacherLookupApi, UserProvisioningApi userProvisioningApi,
			ApplicationEventPublisher eventPublisher, EntityManager entityManager,
			PlatformTransactionManager transactionManager,
			@Value("${app.content.library.max-file-size-bytes:${app.content.material.max-file-size-bytes}}") long maxFileSizeBytes) {
		this.mediaFileRepository = mediaFileRepository;
		this.materialRepository = materialRepository;
		this.accessGuard = accessGuard;
		this.objectStorageApi = objectStorageApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.teacherLookupApi = teacherLookupApi;
		this.userProvisioningApi = userProvisioningApi;
		this.eventPublisher = eventPublisher;
		this.entityManager = entityManager;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.maxFileSizeBytes = maxFileSizeBytes;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	/** Newest first. Not transactional: each repository read is, and thumbnails are signed outside any. */
	public Page<MediaFileView> list(String search, MediaFileKind kind, int page, int size) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		PageRequest pageRequest = PageRequest.of(Math.max(page, 0), boundedSize,
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		Page<MediaFile> files = mediaFileRepository.findLive(kind, search, pageRequest);
		List<UUID> ids = files.getContent().stream().map(MediaFile::getId).toList();
		Map<UUID, Integer> attachments = materialRepository.countLessonAttachmentsByMediaFileIds(ids);
		Map<UUID, String> names = uploaderNames(
				files.getContent().stream().map(MediaFile::getUploadedBy).collect(Collectors.toSet()));
		CallerRights rights = accessGuard.callerRights();
		return files.map(file -> toView(file, attachments.getOrDefault(file.getId(), 0),
				names.get(file.getUploadedBy()), rights, thumbnailUrl(file)));
	}

	public MediaFileSummaryView summary() {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		long documents = mediaFileRepository.countLiveByKind(MediaFileKind.DOCUMENT);
		long images = mediaFileRepository.countLiveByKind(MediaFileKind.IMAGE);
		long other = mediaFileRepository.countLiveByKind(MediaFileKind.OTHER);
		return new MediaFileSummaryView(documents, images, other, documents + images + other);
	}

	/**
	 * A 5-minute signed URL that opens the file inline, served with the stored
	 * (server-sniffed) content type - never a client-declared one - and the
	 * original filename.
	 */
	public SignedDownloadUrl openUrl(UUID id) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		MediaFile file = mediaFileRepository.findLiveById(id)
			.orElseThrow(() -> new NotFoundException("Media file not found"));
		return objectStorageApi.generateSignedDownloadUrl(file.getObjectKey(), OPEN_URL_TTL,
				DownloadOverrides.inline(file.getContentType(), file.getOriginalFilename()));
	}

	// ------------------------------------------------------------------
	// Upload.
	// ------------------------------------------------------------------

	/**
	 * Validates (bounded size, content sniffing against the material
	 * allow-list - PDF, PNG, JPEG, GIF, UTF-8 text; R2-D3) BEFORE anything is
	 * written; a rejected upload leaves no stored object and no row.
	 */
	public MediaFileView upload(MultipartFile file, String requestedTitle) {
		accessGuard.requireLibraryAccess(PermissionAction.CREATE_EDIT);
		if (file == null || file.isEmpty()) {
			throw new FieldValidationException("A file is required",
					List.of(new FieldError("file", "A file upload is required")));
		}
		String originalFilename = sanitizeFilename(file.getOriginalFilename());
		String title = resolveTitle(requestedTitle, originalFilename);

		byte[] bytes = readBoundedBytes(file, maxFileSizeBytes);
		if (bytes.length == 0) {
			throw new FieldValidationException("A file is required",
					List.of(new FieldError("file", "The uploaded file is empty")));
		}
		String contentType = ContentSniffer.sniff(bytes);
		if (contentType == null || ("text/plain".equals(contentType) && looksLikeMarkup(bytes))) {
			throw new UnsupportedMediaTypeException(
					"The uploaded file's content does not match an accepted format (PDF, image, or plain text)");
		}
		MediaFileKind kind = MediaFileKind.fromSniffedContentType(contentType);
		String sha256 = sha256Hex(bytes);
		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();

		// Outbound call first, with no DB transaction open.
		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId, new ByteArrayInputStream(bytes),
				contentType, bytes.length, originalFilename));
		MediaFile saved;
		try {
			saved = transactionTemplate.execute(status -> {
				MediaFile created = mediaFileRepository.save(new MediaFile(tenantId, kind, title, originalFilename,
						contentType, stored.sizeBytes(), stored.objectKey(), sha256, actorId));
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put("title", title);
				metadata.put("kind", kind.name());
				metadata.put("contentType", contentType);
				metadata.put("sizeBytes", stored.sizeBytes());
				metadata.put("originalFilename", originalFilename);
				metadata.put("sha256", sha256);
				auditLogApi.record(new AuditLogEntry(actorId, "media_file.uploaded", TARGET_ENTITY, created.getId(),
						null, metadata));
				return created;
			});
		}
		catch (RuntimeException e) {
			deleteStoredObjectQuietly(stored.objectKey(), null);
			throw e;
		}
		return toView(saved, 0, uploaderNames(Set.of(actorId)).get(actorId), accessGuard.callerRights(),
				thumbnailUrl(saved));
	}

	// ------------------------------------------------------------------
	// Rename / delete.
	// ------------------------------------------------------------------

	@Transactional
	public MediaFileView rename(UUID id, String rawTitle) {
		accessGuard.requireLibraryAccess(PermissionAction.CREATE_EDIT);
		MediaFile file = mediaFileRepository.findLiveById(id)
			.orElseThrow(() -> new NotFoundException("Media file not found"));
		accessGuard.requireManage(file, PermissionAction.CREATE_EDIT);
		String title = validateTitle(rawTitle);
		String previous = file.getTitle();
		if (!previous.equals(title)) {
			file.rename(title);
			mediaFileRepository.save(file);
			auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), "media_file.renamed",
					TARGET_ENTITY, file.getId(), null, Map.of("from", previous, "to", title)));
		}
		Map<UUID, Integer> attachments = materialRepository.countLessonAttachmentsByMediaFileIds(List.of(id));
		return toView(file, attachments.getOrDefault(id, 0), uploaderNames(Set.of(file.getUploadedBy()))
			.get(file.getUploadedBy()), accessGuard.callerRights(), null);
	}

	/**
	 * Soft delete. Refused (409 {@code MEDIA_ATTACHED}) while any live lesson
	 * material uses the file. The row is locked ({@code FOR UPDATE}) so a
	 * concurrent attach ({@link #lockLiveForAttach}) cannot slip in between the
	 * check and the delete. The stored object is removed after commit.
	 */
	@Transactional
	public void delete(UUID id) {
		accessGuard.requireLibraryAccess(PermissionAction.DELETE);
		MediaFile file = mediaFileRepository.findLiveById(id)
			.orElseThrow(() -> new NotFoundException("Media file not found"));
		accessGuard.requireManage(file, PermissionAction.DELETE);
		entityManager.refresh(file, LockModeType.PESSIMISTIC_WRITE);
		if (file.isDeleted()) {
			throw new NotFoundException("Media file not found");
		}
		long attached = materialRepository.countByMediaFileId(id);
		if (attached > 0) {
			throw new ProviderConflictException(ApiErrorCodes.MEDIA_ATTACHED, "This file is used in " + attached
					+ (attached == 1 ? " lesson material" : " lesson materials") + ". Remove it from the lessons first.");
		}
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		file.markDeleted(actorId, Instant.now());
		mediaFileRepository.save(file);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("title", file.getTitle());
		metadata.put("kind", file.getKind().name());
		metadata.put("originalFilename", file.getOriginalFilename());
		metadata.put("uploadedBy", file.getUploadedBy().toString());
		auditLogApi.record(new AuditLogEntry(actorId, "media_file.deleted", TARGET_ENTITY, file.getId(), null,
				metadata));
		eventPublisher.publishEvent(new MediaFileDeletedEvent(file.getTenantId(), file.getId(), file.getObjectKey()));
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onMediaFileDeleted(MediaFileDeletedEvent event) {
		deleteStoredObjectQuietly(event.objectKey(), event.mediaFileId());
	}

	// ------------------------------------------------------------------
	// Lesson-material integration (same module: contentmanagement).
	// ------------------------------------------------------------------

	/**
	 * Locks ({@code SELECT ... FOR UPDATE}) and returns a live library file of
	 * the caller's tenant for attaching to a lesson. Must run inside the
	 * caller's transaction (the lock is held until it commits). Unknown,
	 * other-tenant and deleted ids are all "not found".
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public MediaFile lockLiveForAttach(UUID id) {
		MediaFile file = mediaFileRepository.findLiveById(id)
			.orElseThrow(() -> new NotFoundException("Media file not found"));
		entityManager.refresh(file, LockModeType.PESSIMISTIC_WRITE);
		if (file.isDeleted()) {
			throw new NotFoundException("Media file not found");
		}
		return file;
	}

	/**
	 * The stored object key behind a library-backed material (tenant-scoped).
	 * Empty when the file is unknown in this tenant or deleted (its object is
	 * gone).
	 */
	public Optional<String> findLiveObjectKey(UUID mediaFileId) {
		return mediaFileRepository.findLiveById(mediaFileId).map(MediaFile::getObjectKey);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private MediaFileView toView(MediaFile file, int attachedLessonCount, String uploaderName, CallerRights rights,
			String thumbnailUrl) {
		return new MediaFileView(file.getId(), file.getKind(), file.getTitle(), file.getOriginalFilename(),
				file.getContentType(), file.getSizeBytes(), file.getUploadedBy(), uploaderName, file.getCreatedAt(),
				attachedLessonCount, thumbnailUrl, rights.canEdit(file), rights.canDelete(file));
	}

	/** Images only; a storage outage degrades to "no thumbnail" rather than failing the whole listing. */
	private String thumbnailUrl(MediaFile file) {
		if (file.getKind() != MediaFileKind.IMAGE) {
			return null;
		}
		try {
			return objectStorageApi
				.generateSignedDownloadUrl(file.getObjectKey(), OPEN_URL_TTL,
						DownloadOverrides.inline(file.getContentType(), file.getOriginalFilename()))
				.url();
		}
		catch (RuntimeException e) {
			log.debug("Could not sign a thumbnail URL for media file {}", file.getId(), e);
			return null;
		}
	}

	/** Teacher display names, falling back to the account email for other staff (staff-only endpoint). */
	private Map<UUID, String> uploaderNames(Set<UUID> userIds) {
		Map<UUID, String> names = new HashMap<>();
		if (userIds.isEmpty()) {
			return names;
		}
		teacherLookupApi.listTeachers()
			.stream()
			.filter(teacher -> userIds.contains(teacher.userId()) && teacher.name() != null
					&& !teacher.name().isBlank())
			.forEach(teacher -> names.put(teacher.userId(), teacher.name()));
		List<UUID> missing = userIds.stream().filter(id -> !names.containsKey(id)).toList();
		if (!missing.isEmpty()) {
			for (TenantUserSummary summary : userProvisioningApi.findTenantUserSummaries(missing)) {
				if (summary.email() != null) {
					names.putIfAbsent(summary.userId(), summary.email());
				}
			}
		}
		return names;
	}

	private void deleteStoredObjectQuietly(String objectKey, UUID mediaFileId) {
		try {
			objectStorageApi.delete(objectKey);
		}
		catch (RuntimeException e) {
			log.warn("Failed to delete storage object {} for media file {}", objectKey, mediaFileId, e);
		}
	}

	private static String resolveTitle(String requestedTitle, String originalFilename) {
		if (requestedTitle != null && !requestedTitle.isBlank()) {
			return validateTitle(requestedTitle);
		}
		String base = originalFilename;
		int dot = base.lastIndexOf('.');
		if (dot > 0) {
			base = base.substring(0, dot);
		}
		base = base.strip();
		if (base.isEmpty()) {
			base = originalFilename;
		}
		return base.length() > MediaFile.MAX_TITLE_LENGTH ? base.substring(0, MediaFile.MAX_TITLE_LENGTH) : base;
	}

	private static String validateTitle(String rawTitle) {
		String title = rawTitle == null ? "" : rawTitle.strip();
		if (title.isEmpty()) {
			throw new FieldValidationException("Invalid title", List.of(new FieldError("title", "Title is required")));
		}
		if (title.length() > MediaFile.MAX_TITLE_LENGTH) {
			throw new FieldValidationException("Invalid title", List.of(
					new FieldError("title", "Title must be at most " + MediaFile.MAX_TITLE_LENGTH + " characters")));
		}
		if (title.chars().anyMatch(c -> c < 0x20)) {
			throw new FieldValidationException("Invalid title",
					List.of(new FieldError("title", "Title must not contain control characters")));
		}
		return title;
	}

	/**
	 * R2-D3 "no SVG": an SVG (or HTML/XML) document is valid UTF-8 text, so
	 * {@link ContentSniffer} would accept it as {@code text/plain}. The
	 * library refuses text that starts like markup ({@code <?xml}, {@code <svg},
	 * {@code <!doctype}, {@code <html}, {@code <!--}, after an optional BOM and
	 * whitespace) instead of storing an active-content file as "notes".
	 */
	static boolean looksLikeMarkup(byte[] bytes) {
		int length = Math.min(bytes.length, 1024);
		String head = new String(bytes, 0, length, java.nio.charset.StandardCharsets.UTF_8);
		if (!head.isEmpty() && head.charAt(0) == '﻿') {
			head = head.substring(1);
		}
		head = head.stripLeading().toLowerCase(java.util.Locale.ROOT);
		return head.startsWith("<?xml") || head.startsWith("<svg") || head.startsWith("<!doctype")
				|| head.startsWith("<html") || head.startsWith("<!--");
	}

	static String sha256Hex(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

	/** Basename only, control characters stripped, at most 255 characters (mirrors MaterialService). */
	static String sanitizeFilename(String rawFilename) {
		String name = rawFilename == null ? "upload" : rawFilename;
		int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (lastSlash >= 0) {
			name = name.substring(lastSlash + 1);
		}
		name = name.chars()
			.filter(c -> c >= 0x20 && c != 0x7F)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString()
			.strip();
		if (name.isEmpty()) {
			name = "upload";
		}
		return name.length() > 255 ? name.substring(0, 255) : name;
	}

	/**
	 * Reads at most {@code maxBytes}, aborting with 413 as soon as the running
	 * count exceeds it - never trusts the declared size, never buffers an
	 * oversized upload in full.
	 */
	private static byte[] readBoundedBytes(MultipartFile file, long maxBytes) {
		try (InputStream input = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[STREAM_READ_CHUNK_SIZE];
			long total = 0;
			int read;
			while ((read = input.read(chunk)) != -1) {
				total += read;
				if (total > maxBytes) {
					throw new PayloadTooLargeException("The uploaded file exceeds the maximum allowed size");
				}
				buffer.write(chunk, 0, read);
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read uploaded file", e);
		}
	}

}
