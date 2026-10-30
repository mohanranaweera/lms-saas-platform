package com.lms.contentmanagement.api;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * Narrow contract the exam module uses for question images (Klass Exam Module Slice A,
 * media usage {@code EXAM_QUESTION}, V109). Every method is tenant-scoped through the
 * resolved {@code TenantContext}; no method accepts a tenant id. Authorization of the
 * caller is the exam module's job ({@code ExamAccessGuard}) - this API performs none,
 * so it must only be called after that check passed.
 */
public interface ExamQuestionMediaApi {

	/** Hard upper bound for an exam question image (5 MB). */
	long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

	/**
	 * Validates (bounded size, magic-byte sniffing: PNG, JPEG, GIF, WebP only) and stores
	 * an image as a {@code media_file} of usage {@code EXAM_QUESTION}; audit-logged. A
	 * rejected file leaves no stored object and no row.
	 * @param signedUrlTtl lifetime of the returned preview URL
	 */
	UploadedImage uploadImage(MultipartFile file, Duration signedUrlTtl);

	/** The subset of {@code mediaIds} that are live {@code EXAM_QUESTION} files of the caller's tenant. */
	Set<UUID> findLiveImageIds(Collection<UUID> mediaIds);

	/**
	 * Short-lived inline signed URLs for the live {@code EXAM_QUESTION} files among
	 * {@code mediaIds}; unknown, deleted or other-tenant ids are absent. A storage outage
	 * degrades to "no URL" for that image rather than failing the read.
	 */
	Map<UUID, String> signImageUrls(Collection<UUID> mediaIds, Duration ttl);

	record UploadedImage(UUID mediaId, String url) {
	}

}
