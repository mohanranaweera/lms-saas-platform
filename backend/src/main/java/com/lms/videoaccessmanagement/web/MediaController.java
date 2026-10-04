package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.service.MediaAssetService;
import com.lms.videoaccessmanagement.service.MediaAssetService.MediaAssetListQuery;
import com.lms.videoaccessmanagement.service.MediaAssetView;
import com.lms.videoaccessmanagement.web.dto.MediaAssetResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Media Library (VIDEO Phase 2, {@code /api/v1/media}): tenant-scoped,
 * provider-neutral reads. Stays thin - authorization and tenant scoping live
 * in {@link MediaAssetService}. Any {@code tenantId} a client sends is
 * ignored; the tenant always comes from the authenticated context.
 */
@RestController
@RequestMapping("/api/v1/media")
@Validated
public class MediaController {

	private final MediaAssetService mediaAssetService;

	public MediaController(MediaAssetService mediaAssetService) {
		this.mediaAssetService = mediaAssetService;
	}

	@GetMapping
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PageResponse<MediaAssetResponse>>> list(
			@RequestParam(required = false) VideoAssetStatus status,
			@RequestParam(required = false) MediaAssetProvider provider,
			@RequestParam(name = "q", required = false) @Size(max = 200) String search,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		PageResponse<MediaAssetResponse> body = PageResponse.from(mediaAssetService
			.list(new MediaAssetListQuery(status, provider, search, page, size))
			.map(MediaController::toResponse));
		return ResponseEntity.ok(ApiResponse.success(body));
	}

	@GetMapping("/{id}")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<MediaAssetResponse>> get(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(toResponse(mediaAssetService.get(id))));
	}

	private static MediaAssetResponse toResponse(MediaAssetView view) {
		return new MediaAssetResponse(view.id(), view.provider(), view.title(), view.description(),
				view.thumbnailUrl(), view.durationSeconds(), view.privacyStatus(), view.processingStatus(),
				view.failureReason(), view.uploadedBy(), view.createdAt(), view.capabilities(),
				view.enabledPlaybackModes());
	}

}
