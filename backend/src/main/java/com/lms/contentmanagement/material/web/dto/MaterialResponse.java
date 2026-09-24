package com.lms.contentmanagement.material.web.dto;

import com.lms.contentmanagement.material.domain.MaterialType;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import java.time.Instant;
import java.util.UUID;

/**
 * Metadata only - never a raw storage URL, byte stream, or {@code
 * storageObjectKey} (see {@code MaterialDownloadUrlResponse} for the
 * signed-URL/external-URL fetch path). Extended in Wave 5 (plan §3/§4) with
 * the {@code materialType} discriminator and its per-type fields ({@code
 * externalUrl}/{@code noteContent}/{@code videoAssetId}), the optional
 * {@code sessionId} association, and the download-limit/availability-window
 * fields. {@code downloadCount} is read-only/response-only - there is no
 * corresponding request field on {@code MaterialUpdateRequest}, since it is
 * server-maintained exclusively via {@code
 * MaterialRepository#incrementDownloadCountIfUnderLimit}.
 */
public record MaterialResponse(UUID id, UUID lessonId, UUID sessionId, MaterialType materialType, String title,
		String originalFilename, String mimeType, Long sizeBytes, String externalUrl, String noteContent,
		UUID videoAssetId, Integer sequence, MaterialVisibility visibility, Integer maxDownloads,
		Integer downloadCount, Instant availableFromAt, Instant expiryAt, UUID uploadedBy, Instant createdAt,
		Instant updatedAt) {

}
