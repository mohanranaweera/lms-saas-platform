package com.lms.coursemanagement.course.service;

import com.lms.common.error.NotFoundException;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.error.UnsupportedMediaTypeException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseAuditEvent;
import com.lms.coursemanagement.course.domain.Course;
import com.lms.coursemanagement.course.repository.CourseRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.StoreObjectCommand;
import com.lms.integrationmanagement.api.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * KLASS-T4a course cover image upload/removal. Mirrors {@code
 * SlipUploadService}'s discipline: this class is deliberately NOT
 * {@code @Transactional} because it calls {@link ObjectStorageApi} (an
 * outbound dependency); the database steps run in short {@link
 * TransactionTemplate} transactions around it.
 *
 * <ol>
 * <li>Authorize ({@code CREATE_EDIT} on the course, tenant-scoped load).</li>
 * <li>Bounded read (&le; {@link #MAX_BYTES}, aborts early), then magic-byte
 * sniffing - JPEG, PNG or WebP only; the client's Content-Type and file name
 * are never trusted. Any failure happens before storage is touched.</li>
 * <li>Store (object key {@code <tenantId>/<random uuid>} from the storage
 * adapter - tenant-scoped, unguessable, never returned to clients).</li>
 * <li>Re-authorize and swap the key in a new transaction (audited); if that
 * fails the just-stored object is deleted (no orphan, no partial write).
 * The previous object is deleted best-effort only after the commit.</li>
 * </ol>
 * Clients only ever see a short-lived signed URL ({@code coverImageUrl}).
 */
@Service
public class CourseCoverService {

	private static final Logger log = LoggerFactory.getLogger(CourseCoverService.class);

	static final long MAX_BYTES = 5L * 1024 * 1024;

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private final CourseRepository courseRepository;

	private final CourseAccessGuard courseAccessGuard;

	private final ObjectStorageApi objectStorageApi;

	private final TenantContext tenantContext;

	private final org.springframework.context.ApplicationEventPublisher eventPublisher;

	private final TransactionTemplate transactionTemplate;

	public CourseCoverService(CourseRepository courseRepository, CourseAccessGuard courseAccessGuard,
			ObjectStorageApi objectStorageApi, TenantContext tenantContext,
			org.springframework.context.ApplicationEventPublisher eventPublisher,
			PlatformTransactionManager transactionManager) {
		this.courseRepository = courseRepository;
		this.courseAccessGuard = courseAccessGuard;
		this.objectStorageApi = objectStorageApi;
		this.tenantContext = tenantContext;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	public void uploadCover(UUID courseId, MultipartFile file) {
		transactionTemplate.executeWithoutResult(status -> authorize(courseId));

		byte[] bytes = readBounded(file);
		String mimeType = sniff(bytes);
		if (mimeType == null) {
			throw new UnsupportedMediaTypeException("The cover image must be a JPEG, PNG or WebP image");
		}

		UUID tenantId = tenantContext.getTenantId();
		StoredObject stored = objectStorageApi.store(new StoreObjectCommand(tenantId, new ByteArrayInputStream(bytes),
				mimeType, bytes.length, "course-cover"));
		String previousKey;
		try {
			previousKey = transactionTemplate.execute(status -> swapKey(courseId, stored.objectKey(), mimeType));
		}
		catch (RuntimeException failure) {
			deleteQuietly(stored.objectKey());
			throw failure;
		}
		if (previousKey != null) {
			deleteQuietly(previousKey);
		}
	}

	public void removeCover(UUID courseId) {
		String previousKey = transactionTemplate.execute(status -> swapKey(courseId, null, null));
		if (previousKey != null) {
			deleteQuietly(previousKey);
		}
	}

	private Course authorize(UUID courseId) {
		Course course = courseRepository.findById(courseId)
			.orElseThrow(() -> new NotFoundException("Course not found"));
		courseAccessGuard.requireCourseAccess(course, PermissionAction.CREATE_EDIT);
		return course;
	}

	private String swapKey(UUID courseId, String newKey, String mimeType) {
		Course course = authorize(courseId);
		String previousKey = course.getCoverImageObjectKey();
		if (newKey == null && previousKey == null) {
			return null;
		}
		course.setCoverImageObjectKey(newKey);
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("hadPreviousCover", previousKey != null);
		metadata.put("contentType", mimeType);
		eventPublisher.publishEvent(new CourseAuditEvent(tenantContext.getTenantId(),
				AuthenticatedPrincipalHolder.get().userId(), newKey == null ? "course.cover_removed"
						: "course.cover_changed",
				"course", course.getId(), metadata, Instant.now()));
		return previousKey;
	}

	private void deleteQuietly(String objectKey) {
		try {
			objectStorageApi.delete(objectKey);
		}
		catch (RuntimeException ex) {
			log.warn("Could not delete course cover object", ex);
		}
	}

	/** JPEG / PNG / WebP ("RIFF" .... "WEBP") magic bytes, else {@code null}. Package-visible for tests. */
	static String sniff(byte[] bytes) {
		if (startsWith(bytes, PNG, 0)) {
			return "image/png";
		}
		if (startsWith(bytes, JPEG, 0)) {
			return "image/jpeg";
		}
		if (bytes.length >= 12 && startsWith(bytes, new byte[] { 'R', 'I', 'F', 'F' }, 0)
				&& startsWith(bytes, new byte[] { 'W', 'E', 'B', 'P' }, 8)) {
			return "image/webp";
		}
		return null;
	}

	private static boolean startsWith(byte[] bytes, byte[] signature, int offset) {
		if (bytes.length < offset + signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if (bytes[offset + i] != signature[i]) {
				return false;
			}
		}
		return true;
	}

	private static byte[] readBounded(MultipartFile file) {
		try (InputStream input = file.getInputStream()) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			long total = 0;
			int read;
			while ((read = input.read(chunk)) != -1) {
				total += read;
				if (total > MAX_BYTES) {
					throw new PayloadTooLargeException("The cover image must be at most 5 MB");
				}
				buffer.write(chunk, 0, read);
			}
			if (total == 0) {
				throw new UnsupportedMediaTypeException("The cover image must be a JPEG, PNG or WebP image");
			}
			return buffer.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Failed to read the uploaded cover image", e);
		}
	}

}
