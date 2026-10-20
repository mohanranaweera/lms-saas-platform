package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.integrationmanagement.api.MediaStagingApi.CompletedPart;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.service.MediaAssetService;
import com.lms.videoaccessmanagement.service.MediaAssetService.MediaAssetListQuery;
import com.lms.videoaccessmanagement.service.MediaAssetService.MediaTabs;
import com.lms.videoaccessmanagement.service.MediaAssetService.MediaAssetUpdate;
import com.lms.videoaccessmanagement.service.MediaAssetView;
import com.lms.videoaccessmanagement.service.MediaUploadService;
import com.lms.videoaccessmanagement.service.MediaUploadService.CreateUploadCommand;
import com.lms.videoaccessmanagement.service.MediaUploadService.StartedUpload;
import com.lms.videoaccessmanagement.service.MediaUploadService.UploadInstructions;
import com.lms.videoaccessmanagement.service.MediaUploadService.UploadLimits;
import com.lms.videoaccessmanagement.web.dto.MediaAssetResponse;
import com.lms.videoaccessmanagement.web.dto.MediaAssetResponse.UploadProgressResponse;
import com.lms.videoaccessmanagement.web.dto.MediaUploadDtos.CompleteUploadRequest;
import com.lms.videoaccessmanagement.web.dto.MediaUploadDtos.CreateMediaUploadRequest;
import com.lms.videoaccessmanagement.web.dto.MediaUploadDtos.PresignPartsRequest;
import com.lms.videoaccessmanagement.web.dto.MediaUploadDtos.StartedUploadResponse;
import com.lms.videoaccessmanagement.web.dto.MediaUploadDtos.UpdateMediaRequest;
import com.lms.videoaccessmanagement.web.dto.MediaUploadDtos.UploadInstructionsResponse;
import com.lms.videoaccessmanagement.web.dto.MediaUploadDtos.UploadPartResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Media Library (VIDEO Phase 2, {@code /api/v1/media}): tenant-scoped,
 * provider-neutral reads, plus the Phase 4 YouTube upload flow (create →
 * browser PUTs parts to S3 → complete → background transfer). Stays thin -
 * authorization and tenant scoping live in {@link MediaAssetService} and
 * {@link MediaUploadService}. Any {@code tenantId} a client sends is
 * ignored; the tenant always comes from the authenticated context.
 */
@RestController
@RequestMapping("/api/v1/media")
@Validated
public class MediaController {

	private final MediaAssetService mediaAssetService;

	private final MediaUploadService mediaUploadService;

	public MediaController(MediaAssetService mediaAssetService, MediaUploadService mediaUploadService) {
		this.mediaAssetService = mediaAssetService;
		this.mediaUploadService = mediaUploadService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<MediaAssetResponse>>> list(
			@RequestParam(required = false) VideoAssetStatus status,
			@RequestParam(required = false) MediaAssetProvider provider,
			@RequestParam(name = "q", required = false) @Size(max = 200) String search,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
			@RequestParam(required = false) UUID integrationId,
			@RequestParam(defaultValue = "false") boolean archived) {
		PageResponse<MediaAssetResponse> body = PageResponse.from(mediaAssetService
			.list(new MediaAssetListQuery(status, provider, search, page, size, integrationId, archived))
			.map(MediaController::toResponse));
		return ResponseEntity.ok(ApiResponse.success(body));
	}

	@GetMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaAssetResponse>> get(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(toResponse(mediaAssetService.get(id))));
	}

	/** VIDEO Phase 5 / 5d: limits and the selected integration's state for the upload panel. */
	@GetMapping("/upload-limits")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<UploadLimits>> uploadLimits(
			@RequestParam(required = false) UUID integrationId,
			@RequestParam(required = false) MediaAssetProvider provider) {
		return ResponseEntity.ok(ApiResponse.success(mediaUploadService.limits(integrationId, provider)));
	}

	/** VIDEO Phase 5d: the Media Library tabs the caller sees. */
	@GetMapping("/channels")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaTabs>> tabs() {
		return ResponseEntity.ok(ApiResponse.success(mediaAssetService.tabs()));
	}

	/** VIDEO Phase 5: edits title / description / privacy (synced to YouTube once the video is there). */
	@PatchMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaAssetResponse>> update(@PathVariable UUID id,
			@Valid @RequestBody UpdateMediaRequest request) {
		return ResponseEntity.ok(ApiResponse.success(toResponse(mediaAssetService.update(id,
				new MediaAssetUpdate(request.title(), request.description(), request.privacyStatus())))));
	}

	/** VIDEO Phase 5: LMS soft delete; the YouTube copy is kept (Q-017). */
	@DeleteMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
		mediaAssetService.delete(id);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	/** VIDEO Phase 4: starts a YouTube upload; returns the presigned S3 part URLs. */
	@PostMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<StartedUploadResponse>> createUpload(
			@Valid @RequestBody CreateMediaUploadRequest request) {
		StartedUpload started = mediaUploadService.create(new CreateUploadCommand(request.title(),
				request.description(), request.privacyStatus(), request.provider(), request.fileName(),
				request.mimeType(), request.sizeBytes(), request.integrationId()));
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(new StartedUploadResponse(toResponse(started.mediaAsset()),
					toResponse(started.upload()))));
	}

	@PostMapping("/{id}/upload/parts")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<UploadInstructionsResponse>> presignParts(@PathVariable UUID id,
			@Valid @RequestBody PresignPartsRequest request) {
		return ResponseEntity
			.ok(ApiResponse.success(toResponse(mediaUploadService.presignParts(id, request.partNumbers()))));
	}

	@PostMapping("/{id}/upload/complete")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaAssetResponse>> completeUpload(@PathVariable UUID id,
			@Valid @RequestBody CompleteUploadRequest request) {
		MediaAssetView view = mediaUploadService.complete(id,
				request.parts().stream().map(p -> new CompletedPart(p.partNumber(), p.etag())).toList());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(toResponse(view)));
	}

	@PostMapping("/{id}/upload/cancel")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaAssetResponse>> cancelUpload(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(toResponse(mediaUploadService.cancel(id))));
	}

	@PostMapping("/{id}/upload/retry")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaAssetResponse>> retryUpload(@PathVariable UUID id) {
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.body(ApiResponse.success(toResponse(mediaUploadService.retry(id))));
	}

	private static MediaAssetResponse toResponse(MediaAssetView view) {
		return new MediaAssetResponse(view.id(), view.provider(), view.title(), view.description(),
				view.thumbnailUrl(), view.durationSeconds(), view.privacyStatus(), view.processingStatus(),
				view.failureReason(), view.uploadedBy(), view.createdAt(), view.capabilities(),
				view.enabledPlaybackModes(),
				(view.uploadProgress() == null) ? null
						: new UploadProgressResponse(view.uploadProgress().state(), view.uploadProgress().bytesSent(),
								view.uploadProgress().totalBytes(), view.uploadProgress().message()),
				view.attached(), view.attachedLessonCount(), view.youtubeUrl(), view.integrationId(), view.canEdit(),
				view.canDelete());
	}

	private static UploadInstructionsResponse toResponse(UploadInstructions instructions) {
		return new UploadInstructionsResponse(instructions.partSizeBytes(), instructions.partCount(),
				instructions.parts()
					.stream()
					.map(part -> new UploadPartResponse(part.partNumber(), part.url(), part.expiresAt()))
					.toList());
	}

}
