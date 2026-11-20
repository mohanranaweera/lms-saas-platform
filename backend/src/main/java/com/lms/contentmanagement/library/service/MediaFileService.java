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
import com.lms.contentmanagement.library.domain.MediaFolder;
import com.lms.contentmanagement.library.domain.MediaFileKind;
import com.lms.contentmanagement.library.domain.MediaFileUsage;
import com.lms.contentmanagement.library.repository.MediaFileRepository;
import com.lms.contentmanagement.library.support.MediaFileAccessGuard;
import com.lms.contentmanagement.library.support.MediaFileAccessGuard.CallerRights;
import com.lms.contentmanagement.library.support.MediaFolderAccess;
import com.lms.contentmanagement.library.support.MediaFolderAccess.Caller;
import com.lms.contentmanagement.library.support.MediaFolderAccess.FolderRights;
import com.lms.contentmanagement.library.support.MediaUserNames;
import com.lms.contentmanagement.library.repository.MediaFolderRepository;
import com.lms.contentmanagement.library.service.MediaFolderViews.Member;
import com.lms.contentmanagement.material.repository.MaterialRepository;
import com.lms.contentmanagement.material.service.ContentSniffer;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.integrationmanagement.api.DownloadOverrides;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
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

	private final MediaFolderAccess folderAccess;

	private final MediaFolderRepository folderRepository;

	private final MediaUserNames userNames;

	private final ApplicationEventPublisher eventPublisher;

	private final EntityManager entityManager;

	private final TransactionTemplate transactionTemplate;

	private final long maxFileSizeBytes;

	public MediaFileService(MediaFileRepository mediaFileRepository, MaterialRepository materialRepository,
			MediaFileAccessGuard accessGuard, ObjectStorageApi objectStorageApi, AuditLogApi auditLogApi,
			TenantContext tenantContext, MediaFolderAccess folderAccess, MediaFolderRepository folderRepository,
			MediaUserNames userNames, ApplicationEventPublisher eventPublisher, EntityManager entityManager,
			PlatformTransactionManager transactionManager,
			@Value("${app.content.library.max-file-size-bytes:${app.content.material.max-file-size-bytes}}") long maxFileSizeBytes) {
		this.mediaFileRepository = mediaFileRepository;
		this.materialRepository = materialRepository;
		this.accessGuard = accessGuard;
		this.objectStorageApi = objectStorageApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.folderAccess = folderAccess;
		this.folderRepository = folderRepository;
		this.userNames = userNames;
		this.eventPublisher = eventPublisher;
		this.entityManager = entityManager;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.maxFileSizeBytes = maxFileSizeBytes;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	/** As {@link #list(String, MediaFileKind, UUID, boolean, UUID, int, int)} over every file the caller may view. */
	public Page<MediaFileView> list(String search, MediaFileKind kind, int page, int size) {
		return list(search, kind, null, false, null, page, size);
	}

	/**
	 * Newest first. Not transactional: each repository read is, and thumbnails are signed outside any.
	 * Folder scope (MEDIA-FOLDERS-01): {@code folderId} = that folder (404 when the caller may not
	 * view it); {@code unfiled} = files with no folder; neither = every file the caller may view
	 * (Unfiled plus files in viewable folders). {@code uploadedBy} narrows to one uploader.
	 */
	public Page<MediaFileView> list(String search, MediaFileKind kind, UUID folderId, boolean unfiled, UUID uploadedBy,
			int page, int size) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Specification<MediaFile> scope = readScope(folderId, unfiled, uploadedBy);
		int boundedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		PageRequest pageRequest = PageRequest.of(Math.max(page, 0), boundedSize,
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		Page<MediaFile> files = mediaFileRepository.findLive(kind, search, scope, pageRequest);
		List<UUID> ids = files.getContent().stream().map(MediaFile::getId).toList();
		Map<UUID, Integer> attachments = materialRepository.countLessonAttachmentsByMediaFileIds(ids);
		Map<UUID, String> names = userNames
			.names(files.getContent().stream().map(MediaFile::getUploadedBy).collect(Collectors.toSet()));
		CallerRights rights = accessGuard.callerRights();
		return files.map(file -> toView(file, attachments.getOrDefault(file.getId(), 0),
				names.get(file.getUploadedBy()), rights, thumbnailUrl(file)));
	}

	/** Counts of the files the caller may view only (files in hidden folders are never counted). */
	public MediaFileSummaryView summary() {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Specification<MediaFile> scope = readScope(null, false, null);
		long documents = mediaFileRepository.countLiveByKind(MediaFileKind.DOCUMENT, scope);
		long images = mediaFileRepository.countLiveByKind(MediaFileKind.IMAGE, scope);
		long other = mediaFileRepository.countLiveByKind(MediaFileKind.OTHER, scope);
		return new MediaFileSummaryView(documents, images, other, documents + images + other);
	}

	/** Uploader options ({@code userId}, name) over the viewable files in the folder scope, by name. */
	public List<Member> uploaders(UUID folderId, boolean unfiled) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Specification<MediaFile> scope = readScope(folderId, unfiled, null);
		Set<UUID> uploaderIds = new java.util.HashSet<>(mediaFileRepository.findLiveUploaderIds(scope));
		Map<UUID, String> names = userNames.names(uploaderIds);
		return uploaderIds.stream()
			.map(userId -> new Member(userId, names.get(userId)))
			.sorted(java.util.Comparator
				.comparing((Member member) -> member.name() == null ? "" : member.name().toLowerCase(java.util.Locale.ROOT))
				.thenComparing(Member::userId))
			.toList();
	}

	/**
	 * A 5-minute signed URL that opens the file inline, served with the stored
	 * (server-sniffed) content type - never a client-declared one - and the
	 * original filename. A file in a folder the caller may not view is "not found".
	 */
	public SignedDownloadUrl openUrl(UUID id) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		MediaFile file = mediaFileRepository.findLiveLibraryById(id)
			.orElseThrow(MediaFileService::fileNotFound);
		requireFileVisible(folderAccess.caller(), file);
		return objectStorageApi.generateSignedDownloadUrl(file.getObjectKey(), OPEN_URL_TTL,
				DownloadOverrides.inline(file.getContentType(), file.getOriginalFilename()));
	}

	private Specification<MediaFile> readScope(UUID folderId, boolean unfiled, UUID uploadedBy) {
		if (folderId != null && unfiled) {
			throw new FieldValidationException("Invalid folder filter",
					List.of(new FieldError("unfiled", "Use either folderId or unfiled, not both")));
		}
		Caller caller = folderAccess.caller();
		if (folderId != null) {
			if (!folderAccess.canViewFolder(caller, folderId)) {
				throw MediaFolderAccess.folderNotFound();
			}
			return MediaFileRepository.scope(folderId, false, uploadedBy, null);
		}
		return MediaFileRepository.scope(null, unfiled, uploadedBy,
				unfiled ? null : folderAccess.visibleFolderIdsOrNull(caller));
	}

	/** Files in a folder the caller may not view are reported as not found (never 403, never counted). */
	private void requireFileVisible(Caller caller, MediaFile file) {
		if (!folderAccess.canViewFolder(caller, file.getFolderId())) {
			throw fileNotFound();
		}
	}

	/**
	 * The caller may upload into {@code folderId}: 404 when it cannot view the folder, 403 when it
	 * can but holds no upload right. {@code fresh}: re-read the folder row from the database (used
	 * under the media-folders lock, so a concurrent transfer / delete / revoke is seen).
	 */
	private void requireUploadableFolder(Caller caller, UUID folderId, boolean fresh) {
		MediaFolder folder = folderRepository.findById(folderId).orElseThrow(MediaFolderAccess::folderNotFound);
		if (fresh) {
			entityManager.refresh(folder);
		}
		FolderRights rights = folderAccess.rights(caller, folder);
		if (!rights.canView()) {
			throw MediaFolderAccess.folderNotFound();
		}
		if (!rights.canUpload()) {
			throw new AccessDeniedException("You do not have permission to upload into this folder");
		}
	}

	private static NotFoundException fileNotFound() {
		return new NotFoundException("Media file not found");
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
		return upload(file, requestedTitle, MediaFileUsage.COURSE_LIBRARY);
	}

	/**
	 * As {@link #upload(MultipartFile, String)}, filed under {@code usage}. A
	 * {@link MediaFileUsage#TEACHER_PROFILE} upload must be an image and is never
	 * listed in the library.
	 */
	public MediaFileView upload(MultipartFile file, String requestedTitle, MediaFileUsage usage) {
		return upload(file, requestedTitle, usage, null);
	}

	/**
	 * As {@link #upload(MultipartFile, String, MediaFileUsage)}, filed in {@code folderId} ({@code
	 * null} = Unfiled; library files only). The caller must be able to upload into the folder
	 * ({@code canUpload}: 404 when it cannot view it, 403 otherwise); this is checked before
	 * anything is stored and re-checked under the tenant's media-folders lock inside the insert
	 * transaction (a concurrent revoke / delete / transfer wins and the stored object is removed).
	 */
	public MediaFileView upload(MultipartFile file, String requestedTitle, MediaFileUsage usage, UUID folderId) {
		accessGuard.requireLibraryAccess(PermissionAction.CREATE_EDIT);
		MediaFileUsage usageArea = usage == null ? MediaFileUsage.COURSE_LIBRARY : usage;
		if (folderId != null) {
			if (usageArea != MediaFileUsage.COURSE_LIBRARY) {
				throw new FieldValidationException("Invalid folder",
						List.of(new FieldError("folderId", "Only library files can be filed in a folder")));
			}
			requireUploadableFolder(folderAccess.caller(), folderId, false);
		}
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
		if (usageArea == MediaFileUsage.TEACHER_PROFILE && kind != MediaFileKind.IMAGE) {
			throw new UnsupportedMediaTypeException("A profile photo must be a PNG, JPEG or GIF image");
		}
		String sha256 = sha256Hex(bytes);
		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();

		// Outbound call first, with no DB transaction open.
		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId, new ByteArrayInputStream(bytes),
				contentType, bytes.length, originalFilename));
		MediaFile saved;
		try {
			saved = transactionTemplate.execute(status -> {
				if (folderId != null) {
					folderAccess.lockTree();
					requireUploadableFolder(folderAccess.caller(), folderId, true);
				}
				MediaFile toCreate = new MediaFile(tenantId, kind, usageArea, title, originalFilename, contentType,
						stored.sizeBytes(), stored.objectKey(), sha256, actorId);
				toCreate.moveToFolder(folderId);
				MediaFile created = mediaFileRepository.save(toCreate);
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put("title", title);
				if (folderId != null) {
					metadata.put("folderId", folderId.toString());
				}
				metadata.put("kind", kind.name());
				metadata.put("usageArea", usageArea.name());
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
		return toView(saved, 0, userNames.names(Set.of(actorId)).get(actorId), accessGuard.callerRights(),
				thumbnailUrl(saved));
	}

	// ------------------------------------------------------------------
	// Rename / delete.
	// ------------------------------------------------------------------

	@Transactional
	public MediaFileView rename(UUID id, String rawTitle) {
		accessGuard.requireLibraryAccess(PermissionAction.CREATE_EDIT);
		MediaFile file = mediaFileRepository.findLiveLibraryById(id)
			.orElseThrow(MediaFileService::fileNotFound);
		requireFileVisible(folderAccess.caller(), file);
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
		return toView(file, attachments.getOrDefault(id, 0), userNames.names(Set.of(file.getUploadedBy()))
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
		MediaFile file = mediaFileRepository.findLiveLibraryById(id)
			.orElseThrow(MediaFileService::fileNotFound);
		requireFileVisible(folderAccess.caller(), file);
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

	/**
	 * Files a library file in {@code folderId} ({@code null} = Unfiled; MEDIA-FOLDERS-01). Needs
	 * the same file-manage rights as rename, view access to the source folder and upload access
	 * to the destination folder. Only {@code folder_id} changes: id, uploader, object key and
	 * every lesson material attaching the file stay as they are. Runs under the tenant's
	 * media-folders lock with the file row locked.
	 */
	@Transactional
	public MediaFileView move(UUID id, UUID folderId) {
		accessGuard.requireLibraryAccess(PermissionAction.CREATE_EDIT);
		folderAccess.lockTree();
		MediaFile file = mediaFileRepository.findLiveLibraryById(id).orElseThrow(MediaFileService::fileNotFound);
		entityManager.refresh(file, LockModeType.PESSIMISTIC_WRITE);
		if (file.isDeleted()) {
			throw fileNotFound();
		}
		Caller caller = folderAccess.caller();
		requireFileVisible(caller, file);
		accessGuard.requireManage(file, PermissionAction.CREATE_EDIT);
		UUID previousFolderId = file.getFolderId();
		if (!Objects.equals(previousFolderId, folderId)) {
			if (folderId != null) {
				requireUploadableFolder(caller, folderId, true);
			}
			file.moveToFolder(folderId);
			mediaFileRepository.save(file);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("title", file.getTitle());
			if (previousFolderId != null) {
				metadata.put("fromFolderId", previousFolderId.toString());
			}
			if (folderId != null) {
				metadata.put("toFolderId", folderId.toString());
			}
			auditLogApi.record(new AuditLogEntry(caller.userId(), "media_file.moved", TARGET_ENTITY, file.getId(), null,
					metadata));
		}
		Map<UUID, Integer> attachments = materialRepository.countLessonAttachmentsByMediaFileIds(List.of(id));
		return toView(file, attachments.getOrDefault(id, 0), userNames.names(Set.of(file.getUploadedBy()))
			.get(file.getUploadedBy()), accessGuard.callerRights(), null);
	}

	/**
	 * Removes a teacher profile photo that is no longer used (cleared or replaced).
	 * Only {@link MediaFileUsage#TEACHER_PROFILE} files are removed - a library image
	 * that also served as a photo stays - and never one attached to a lesson. The
	 * caller (the teacher module) has already authorized the change and checked no
	 * other teacher uses the file. Soft delete + audit; the object goes after commit.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void discardTeacherProfilePhoto(UUID id) {
		Optional<MediaFile> found = mediaFileRepository.findLiveById(id)
			.filter(file -> file.getUsageArea() == MediaFileUsage.TEACHER_PROFILE);
		if (found.isEmpty()) {
			return;
		}
		MediaFile file = found.get();
		entityManager.refresh(file, LockModeType.PESSIMISTIC_WRITE);
		if (file.isDeleted() || materialRepository.countByMediaFileId(id) > 0) {
			return;
		}
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		file.markDeleted(actorId, Instant.now());
		mediaFileRepository.save(file);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("usageArea", file.getUsageArea().name());
		metadata.put("originalFilename", file.getOriginalFilename());
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
	 * other-tenant, deleted ids and files in a folder the caller may not view
	 * (MEDIA-FOLDERS-01) are all "not found".
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public MediaFile lockLiveForAttach(UUID id) {
		MediaFile file = mediaFileRepository.findLiveLibraryById(id)
			.orElseThrow(MediaFileService::fileNotFound);
		entityManager.refresh(file, LockModeType.PESSIMISTIC_WRITE);
		if (file.isDeleted()) {
			throw fileNotFound();
		}
		requireFileVisible(folderAccess.caller(), file);
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
				attachedLessonCount, thumbnailUrl, rights.canEdit(file), rights.canDelete(file), file.getFolderId());
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
