package com.lms.contentmanagement.library.web.dto;

import com.lms.contentmanagement.library.domain.MediaFileKind;
import java.time.Instant;
import java.util.UUID;

/**
 * A media library item ({@code /api/v1/media-files}). Never carries the
 * storage object key or the content hash. {@code canEdit}/{@code canDelete}
 * are computed server-side for the caller; {@code thumbnailUrl} is a 5-minute
 * signed inline URL for images only ({@code null} otherwise).
 */
public record MediaFileResponse(UUID id, MediaFileKind kind, String title, String originalFilename,
		String contentType, long sizeBytes, UUID uploadedBy, String uploaderName, Instant createdAt,
		int attachedLessonCount, String thumbnailUrl, boolean canEdit, boolean canDelete) {

}
