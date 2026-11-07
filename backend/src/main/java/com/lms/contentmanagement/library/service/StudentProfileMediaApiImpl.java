package com.lms.contentmanagement.library.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.StudentProfileMediaApi;
import com.lms.contentmanagement.library.domain.MediaFile;
import com.lms.contentmanagement.library.domain.MediaFileKind;
import com.lms.contentmanagement.library.domain.MediaFileUsage;
import com.lms.contentmanagement.library.repository.MediaFileRepository;
import com.lms.contentmanagement.material.service.ContentSniffer;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link StudentProfileMediaApi} on the shared {@code media_file} store, with the same
 * ordering as {@link ExamDocumentMediaApiImpl}: validate and normalise fully, store the
 * bytes with no DB transaction open, then insert + audit in one transaction (removing the
 * stored object again if that transaction fails).
 */
@Service
public class StudentProfileMediaApiImpl implements StudentProfileMediaApi {

	static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;

	private static final Duration URL_TTL = Duration.ofMinutes(5);

	private static final int READ_CHUNK = 8192;

	private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/jpeg");

	private static final String STORED_FILENAME = "profile-photo.jpg";

	private static final Logger log = LoggerFactory.getLogger(StudentProfileMediaApiImpl.class);

	private final MediaFileRepository mediaFileRepository;

	private final ObjectStorageApi objectStorageApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final ApplicationEventPublisher eventPublisher;

	private final EntityManager entityManager;

	private final TransactionTemplate transactionTemplate;

	public StudentProfileMediaApiImpl(MediaFileRepository mediaFileRepository, ObjectStorageApi objectStorageApi,
			AuditLogApi auditLogApi, TenantContext tenantContext, ApplicationEventPublisher eventPublisher,
			EntityManager entityManager, PlatformTransactionManager transactionManager) {
		this.mediaFileRepository = mediaFileRepository;
		this.objectStorageApi = objectStorageApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.eventPublisher = eventPublisher;
		this.entityManager = entityManager;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@Override
	public UUID storeProfileImage(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new FieldValidationException("A file is required",
					List.of(new FieldError("file", "A PNG or JPEG photo is required")));
		}
		byte[] bytes = readBounded(file);
		String contentType = ContentSniffer.sniff(bytes);
		if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
			throw new UnsupportedMediaTypeException("The photo must be a PNG or JPEG image");
		}
		byte[] jpeg = ProfileImageNormalizer.normalize(bytes, contentType);
		String sha256 = MediaFileService.sha256Hex(jpeg);
		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();

		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId, new ByteArrayInputStream(jpeg),
				"image/jpeg", jpeg.length, STORED_FILENAME));
		try {
			return transactionTemplate.execute(status -> {
				MediaFile created = mediaFileRepository.save(new MediaFile(tenantId, MediaFileKind.IMAGE,
						MediaFileUsage.STUDENT_PROFILE, "Student profile photo", STORED_FILENAME, "image/jpeg",
						stored.sizeBytes(), stored.objectKey(), sha256, actorId));
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put("usageArea", MediaFileUsage.STUDENT_PROFILE.name());
				metadata.put("contentType", "image/jpeg");
				metadata.put("sizeBytes", stored.sizeBytes());
				metadata.put("sourceContentType", contentType);
				metadata.put("sha256", sha256);
				auditLogApi.record(new AuditLogEntry(actorId, "media_file.uploaded", "media_file", created.getId(), null,
						metadata));
				return created.getId();
			});
		}
		catch (RuntimeException e) {
			try {
				objectStorageApi.delete(stored.objectKey());
			}
			catch (RuntimeException cleanup) {
				log.warn("Failed to delete storage object {} after a failed student photo upload", stored.objectKey(),
						cleanup);
			}
			throw e;
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<SignedImage> signUrl(UUID mediaFileId) {
		if (mediaFileId == null) {
			return Optional.empty();
		}
		return mediaFileRepository.findLiveByIdsAndUsage(Set.of(mediaFileId), MediaFileUsage.STUDENT_PROFILE)
			.stream()
			.findFirst()
			.map(file -> {
				SignedDownloadUrl url = objectStorageApi.generateSignedDownloadUrl(file.getObjectKey(), URL_TTL,
						DownloadOverrides.inline(file.getContentType(), file.getOriginalFilename()));
				return new SignedImage(url.url(), url.expiresAt());
			});
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void discardProfileImage(UUID mediaFileId) {
		if (mediaFileId == null) {
			return;
		}
		Optional<MediaFile> found = mediaFileRepository
			.findLiveByIdsAndUsage(Set.of(mediaFileId), MediaFileUsage.STUDENT_PROFILE)
			.stream()
			.findFirst();
		if (found.isEmpty()) {
			return;
		}
		MediaFile file = found.get();
		entityManager.refresh(file, LockModeType.PESSIMISTIC_WRITE);
		if (file.isDeleted()) {
			return;
		}
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		file.markDeleted(actorId, Instant.now());
		mediaFileRepository.save(file);
		auditLogApi.record(new AuditLogEntry(actorId, "media_file.deleted", "media_file", file.getId(), null,
				Map.of("usageArea", MediaFileUsage.STUDENT_PROFILE.name())));
		eventPublisher.publishEvent(new MediaFileDeletedEvent(file.getTenantId(), file.getId(), file.getObjectKey()));
	}

	private static byte[] readBounded(MultipartFile file) {
		if (file.getSize() > MAX_UPLOAD_BYTES) {
			throw new PayloadTooLargeException("The photo exceeds the 2 MB limit");
		}
		try (InputStream input = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[READ_CHUNK];
			long total = 0;
			int read;
			while ((read = input.read(chunk)) != -1) {
				total += read;
				if (total > MAX_UPLOAD_BYTES) {
					throw new PayloadTooLargeException("The photo exceeds the 2 MB limit");
				}
				buffer.write(chunk, 0, read);
			}
			byte[] bytes = buffer.toByteArray();
			if (bytes.length == 0) {
				throw new FieldValidationException("A file is required",
						List.of(new FieldError("file", "The uploaded file is empty")));
			}
			return bytes;
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read the uploaded photo", e);
		}
	}

}
