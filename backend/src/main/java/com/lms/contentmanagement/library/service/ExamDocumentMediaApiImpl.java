package com.lms.contentmanagement.library.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.ExamDocumentMediaApi;
import com.lms.contentmanagement.library.domain.MediaFile;
import com.lms.contentmanagement.library.domain.MediaFileKind;
import com.lms.contentmanagement.library.domain.MediaFileUsage;
import com.lms.contentmanagement.library.repository.MediaFileRepository;
import com.lms.contentmanagement.material.service.ContentSniffer;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.integrationmanagement.api.DownloadOverrides;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link ExamDocumentMediaApi} on the shared {@code media_file} store, with the same
 * ordering as {@link ExamQuestionMediaApiImpl} (validate fully, store the bytes with no DB
 * transaction open, then insert + audit in one transaction, deleting the stored object
 * again if that transaction fails) and its own allow-list: PDF, PNG and JPEG only, sniffed
 * from the bytes (never the declared type or extension), at most 20 MB.
 */
@Service
public class ExamDocumentMediaApiImpl implements ExamDocumentMediaApi {

	private static final Logger log = LoggerFactory.getLogger(ExamDocumentMediaApiImpl.class);

	private static final int READ_CHUNK = 8192;

	private static final Set<String> ALLOWED_TYPES = Set.of("application/pdf", "image/png", "image/jpeg");

	private final MediaFileRepository mediaFileRepository;

	private final ObjectStorageApi objectStorageApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final TransactionTemplate transactionTemplate;

	public ExamDocumentMediaApiImpl(MediaFileRepository mediaFileRepository, ObjectStorageApi objectStorageApi,
			AuditLogApi auditLogApi, TenantContext tenantContext, PlatformTransactionManager transactionManager) {
		this.mediaFileRepository = mediaFileRepository;
		this.objectStorageApi = objectStorageApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@Override
	public StoredDocument upload(MultipartFile file, Purpose purpose) {
		if (file == null || file.isEmpty()) {
			throw new FieldValidationException("A file is required",
					List.of(new FieldError("file", "A PDF, PNG or JPEG file is required")));
		}
		String originalFilename = MediaFileService.sanitizeFilename(file.getOriginalFilename());
		byte[] bytes = readBounded(file);
		if (bytes.length == 0) {
			throw new FieldValidationException("A file is required",
					List.of(new FieldError("file", "The uploaded file is empty")));
		}
		String contentType = ContentSniffer.sniff(bytes);
		if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
			throw new UnsupportedMediaTypeException("The document must be a PDF, PNG or JPEG file");
		}
		MediaFileUsage usage = usageOf(purpose);
		String sha256 = MediaFileService.sha256Hex(bytes);
		String title = titleFrom(originalFilename);
		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();

		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId, new ByteArrayInputStream(bytes),
				contentType, bytes.length, originalFilename));
		MediaFile saved;
		try {
			saved = transactionTemplate.execute(status -> {
				MediaFile created = mediaFileRepository.save(new MediaFile(tenantId,
						MediaFileKind.fromSniffedContentType(contentType), usage, title, originalFilename, contentType,
						stored.sizeBytes(), stored.objectKey(), sha256, actorId));
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put("usageArea", usage.name());
				metadata.put("contentType", contentType);
				metadata.put("sizeBytes", stored.sizeBytes());
				metadata.put("originalFilename", originalFilename);
				metadata.put("sha256", sha256);
				auditLogApi.record(new AuditLogEntry(actorId, "media_file.uploaded", "media_file", created.getId(),
						null, metadata));
				return created;
			});
		}
		catch (RuntimeException e) {
			try {
				objectStorageApi.delete(stored.objectKey());
			}
			catch (RuntimeException cleanup) {
				log.warn("Failed to delete storage object {} after a failed exam document insert",
						stored.objectKey(), cleanup);
			}
			throw e;
		}
		return toStored(saved);
	}

	@Override
	public Optional<StoredDocument> findLive(UUID mediaId, Purpose purpose) {
		return findFile(mediaId, purpose).map(ExamDocumentMediaApiImpl::toStored);
	}

	@Override
	public Optional<String> signUrl(UUID mediaId, Purpose purpose, Duration ttl) {
		return findFile(mediaId, purpose).map(file -> objectStorageApi
			.generateSignedDownloadUrl(file.getObjectKey(), ttl,
					DownloadOverrides.inline(file.getContentType(), file.getOriginalFilename()))
			.url());
	}

	private Optional<MediaFile> findFile(UUID mediaId, Purpose purpose) {
		if (mediaId == null || purpose == null) {
			return Optional.empty();
		}
		return mediaFileRepository.findLiveByIdsAndUsage(Set.of(mediaId), usageOf(purpose)).stream().findFirst();
	}

	private static StoredDocument toStored(MediaFile file) {
		return new StoredDocument(file.getId(), file.getOriginalFilename(), file.getUploadedBy());
	}

	private static MediaFileUsage usageOf(Purpose purpose) {
		return switch (purpose) {
			case QUESTION_PAPER -> MediaFileUsage.EXAM_QUESTION_PAPER;
			case MARKING_SCHEME -> MediaFileUsage.EXAM_MARKING_SCHEME;
		};
	}

	private static String titleFrom(String originalFilename) {
		String base = originalFilename;
		int dot = base.lastIndexOf('.');
		if (dot > 0) {
			base = base.substring(0, dot);
		}
		base = base.strip();
		if (base.isEmpty()) {
			base = "Exam document";
		}
		return base.length() > MediaFile.MAX_TITLE_LENGTH ? base.substring(0, MediaFile.MAX_TITLE_LENGTH) : base;
	}

	private static byte[] readBounded(MultipartFile file) {
		try (InputStream input = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[READ_CHUNK];
			long total = 0;
			int read;
			while ((read = input.read(chunk)) != -1) {
				total += read;
				if (total > MAX_DOCUMENT_BYTES) {
					throw new PayloadTooLargeException("The document exceeds the 20 MB limit");
				}
				buffer.write(chunk, 0, read);
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read uploaded document", e);
		}
	}

}
