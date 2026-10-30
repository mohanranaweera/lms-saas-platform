package com.lms.contentmanagement.library.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.api.ExamQuestionMediaApi;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link ExamQuestionMediaApi} on top of the shared {@code media_file} store. Mirrors
 * {@link MediaFileService#upload}'s ordering (validate fully, store the bytes with no DB
 * transaction open, then insert + audit in one transaction, deleting the stored object
 * again if that transaction fails) but with an image-only allow-list that also accepts
 * WebP - the Media Library / lesson-material allow-list ({@link ContentSniffer}) is left
 * unchanged.
 */
@Service
public class ExamQuestionMediaApiImpl implements ExamQuestionMediaApi {

	private static final Logger log = LoggerFactory.getLogger(ExamQuestionMediaApiImpl.class);

	private static final int READ_CHUNK = 8192;

	private final MediaFileRepository mediaFileRepository;

	private final ObjectStorageApi objectStorageApi;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final TransactionTemplate transactionTemplate;

	public ExamQuestionMediaApiImpl(MediaFileRepository mediaFileRepository, ObjectStorageApi objectStorageApi,
			AuditLogApi auditLogApi, TenantContext tenantContext, PlatformTransactionManager transactionManager) {
		this.mediaFileRepository = mediaFileRepository;
		this.objectStorageApi = objectStorageApi;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@Override
	public UploadedImage uploadImage(MultipartFile file, Duration signedUrlTtl) {
		if (file == null || file.isEmpty()) {
			throw new FieldValidationException("A file is required",
					List.of(new FieldError("file", "An image file is required")));
		}
		String originalFilename = MediaFileService.sanitizeFilename(file.getOriginalFilename());
		byte[] bytes = readBounded(file);
		if (bytes.length == 0) {
			throw new FieldValidationException("A file is required",
					List.of(new FieldError("file", "The uploaded file is empty")));
		}
		String contentType = sniffImage(bytes);
		if (contentType == null) {
			throw new UnsupportedMediaTypeException("The image must be a PNG, JPEG, GIF or WebP file");
		}
		String sha256 = MediaFileService.sha256Hex(bytes);
		String title = titleFrom(originalFilename);
		UUID tenantId = tenantContext.getTenantId();
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();

		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId, new ByteArrayInputStream(bytes),
				contentType, bytes.length, originalFilename));
		MediaFile saved;
		try {
			saved = transactionTemplate.execute(status -> {
				MediaFile created = mediaFileRepository.save(new MediaFile(tenantId, MediaFileKind.IMAGE,
						MediaFileUsage.EXAM_QUESTION, title, originalFilename, contentType, stored.sizeBytes(),
						stored.objectKey(), sha256, actorId));
				Map<String, Object> metadata = new LinkedHashMap<>();
				metadata.put("usageArea", MediaFileUsage.EXAM_QUESTION.name());
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
				log.warn("Failed to delete storage object {} after a failed exam image insert", stored.objectKey(),
						cleanup);
			}
			throw e;
		}
		return new UploadedImage(saved.getId(), sign(saved, signedUrlTtl));
	}

	@Override
	public Set<UUID> findLiveImageIds(Collection<UUID> mediaIds) {
		if (mediaIds == null || mediaIds.isEmpty()) {
			return Set.of();
		}
		return mediaFileRepository.findLiveByIdsAndUsage(Set.copyOf(mediaIds), MediaFileUsage.EXAM_QUESTION)
			.stream()
			.map(MediaFile::getId)
			.collect(Collectors.toSet());
	}

	@Override
	public Map<UUID, String> signImageUrls(Collection<UUID> mediaIds, Duration ttl) {
		Map<UUID, String> urls = new HashMap<>();
		if (mediaIds == null || mediaIds.isEmpty()) {
			return urls;
		}
		for (MediaFile file : mediaFileRepository.findLiveByIdsAndUsage(Set.copyOf(mediaIds),
				MediaFileUsage.EXAM_QUESTION)) {
			String url = sign(file, ttl);
			if (url != null) {
				urls.put(file.getId(), url);
			}
		}
		return urls;
	}

	private String sign(MediaFile file, Duration ttl) {
		try {
			return objectStorageApi
				.generateSignedDownloadUrl(file.getObjectKey(), ttl,
						DownloadOverrides.inline(file.getContentType(), file.getOriginalFilename()))
				.url();
		}
		catch (RuntimeException e) {
			log.debug("Could not sign a URL for exam image {}", file.getId(), e);
			return null;
		}
	}

	/** PNG / JPEG / GIF via the shared sniffer, plus WebP ({@code RIFF....WEBP}); anything else is null. */
	static String sniffImage(byte[] bytes) {
		if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
			return "image/webp";
		}
		String sniffed = ContentSniffer.sniff(bytes);
		return sniffed != null && sniffed.startsWith("image/") ? sniffed : null;
	}

	private static String titleFrom(String originalFilename) {
		String base = originalFilename;
		int dot = base.lastIndexOf('.');
		if (dot > 0) {
			base = base.substring(0, dot);
		}
		base = base.strip();
		if (base.isEmpty()) {
			base = "Exam image";
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
				if (total > MAX_IMAGE_BYTES) {
					throw new PayloadTooLargeException("The image exceeds the 5 MB limit");
				}
				buffer.write(chunk, 0, read);
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read uploaded image", e);
		}
	}

}
