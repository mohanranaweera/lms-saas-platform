package com.lms.videoaccessmanagement.web.dto;

import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.MediaPrivacyStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Bodies of the VIDEO Phase 4 upload endpoints ({@code /api/v1/media},
 * {@code docs/api/video-access-management.md}). No request carries a tenant
 * id, uploader id or staging key - those come from the authenticated context
 * and the server. No response carries the staging key, the S3 upload id or
 * the provider session.
 */
public final class MediaUploadDtos {

	private MediaUploadDtos() {
	}

	/** Field-level rules are checked (with all errors at once) by {@code MediaUploadService}. */
	public record CreateMediaUploadRequest(@Size(max = 200) String title, @Size(max = 6000) String description,
			MediaPrivacyStatus privacyStatus, MediaAssetProvider provider, @Size(max = 300) String fileName,
			@Size(max = 100) String mimeType, Long sizeBytes) {

	}

	/** VIDEO Phase 5 {@code PATCH /media/{id}}: absent fields stay as they are; a blank description clears it. */
	public record UpdateMediaRequest(@Size(max = 200) String title, @Size(max = 6000) String description,
			MediaPrivacyStatus privacyStatus) {

	}

	public record PresignPartsRequest(@NotNull @Size(min = 1, max = 100) List<Integer> partNumbers) {

	}

	public record CompletedPartRequest(int partNumber, @Size(max = 256) String etag) {

	}

	public record CompleteUploadRequest(@NotNull @Size(min = 1, max = 10_000) List<@Valid CompletedPartRequest> parts) {

	}

	public record UploadPartResponse(int partNumber, String url, Instant expiresAt) {

	}

	public record UploadInstructionsResponse(long partSizeBytes, int partCount, List<UploadPartResponse> parts) {

	}

	public record StartedUploadResponse(MediaAssetResponse mediaAsset, UploadInstructionsResponse upload) {

	}

}
