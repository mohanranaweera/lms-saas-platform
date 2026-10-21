package com.lms.contentmanagement.library.service;

import com.lms.contentmanagement.library.domain.MediaFileKind;
import java.time.Instant;
import java.util.UUID;

/**
 * One library item as seen by the calling staff member. Never carries the
 * storage object key or the sha256.
 *
 * @param uploaderName the uploader's teacher name, or their email for other
 * staff; {@code null} when unknown
 * @param attachedLessonCount distinct lessons a live material attaches this
 * file to
 * @param thumbnailUrl images only: a 5-minute signed inline URL served with
 * the stored (sniffed) content type; {@code null} otherwise
 * @param canEdit the caller may rename this file
 * @param canDelete the caller may delete this file (an attached file is still
 * refused with 409 {@code MEDIA_ATTACHED})
 */
public record MediaFileView(UUID id, MediaFileKind kind, String title, String originalFilename,
		String contentType, long sizeBytes, UUID uploadedBy, String uploaderName, Instant createdAt,
		int attachedLessonCount, String thumbnailUrl, boolean canEdit, boolean canDelete) {

}
