package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.videoaccessmanagement.service.PlaybackPolicyUpdateCommand;
import com.lms.videoaccessmanagement.service.PlaybackSessionView;
import com.lms.videoaccessmanagement.service.VideoAssetService;
import com.lms.videoaccessmanagement.service.VideoAssetView;
import com.lms.videoaccessmanagement.service.VideoPlaybackPolicyView;
import com.lms.videoaccessmanagement.service.VideoPlaybackSessionService;
import com.lms.videoaccessmanagement.support.RequestDeviceFingerprint;
import com.lms.videoaccessmanagement.web.dto.PlaybackProgressRequest;
import com.lms.videoaccessmanagement.web.dto.PlaybackSessionResponse;
import com.lms.videoaccessmanagement.web.dto.VideoAssetResponse;
import com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyRequest;
import com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Video endpoints (Wave 5, {@code /api/v1/videos}), stays thin, delegates
 * entirely to {@link VideoAssetService}/{@link VideoPlaybackSessionService},
 * which perform the real authorization, mirroring {@code
 * MaterialController}'s exact style: {@code @PreAuthorize("isAuthenticated()")}
 * plus real authz in the guard/service, never in the controller.
 *
 * <p>The device fingerprint is computed here, from the raw {@link
 * HttpServletRequest}, and passed into the service layer as a plain {@code
 * String} - mirroring {@code AuthController#login}'s identical established
 * pattern for {@code identityaccessservice.web.DeviceFingerprint} (see
 * {@code RequestDeviceFingerprint}'s own javadoc for why this can't be a
 * request-scoped injection into the service instead).
 */
@RestController
@RequestMapping("/api/v1/videos")
@Validated
public class VideoController {

	private final VideoAssetService videoAssetService;

	private final VideoPlaybackSessionService videoPlaybackSessionService;

	public VideoController(VideoAssetService videoAssetService,
			VideoPlaybackSessionService videoPlaybackSessionService) {
		this.videoAssetService = videoAssetService;
		this.videoPlaybackSessionService = videoPlaybackSessionService;
	}

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<VideoAssetResponse>> upload(@RequestPart("file") MultipartFile file) {
		VideoAssetView view = videoAssetService.upload(file);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toResponse(view)));
	}

	@PutMapping("/{id}/policy")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<VideoPlaybackPolicyResponse>> upsertPolicy(@PathVariable UUID id,
			@Valid @RequestBody VideoPlaybackPolicyRequest request) {
		PlaybackPolicyUpdateCommand command = new PlaybackPolicyUpdateCommand(request.accessStartAt(),
				request.accessEndAt(), request.maxViewsPerStudent(), request.maxWatchDurationSeconds(),
				request.allowSeeking(), request.allowDownload(), request.watermarkEnabled(),
				request.maxConcurrentSessions());
		VideoPlaybackPolicyView view = videoAssetService.upsertPolicy(id, command);
		return ResponseEntity.ok(ApiResponse.success(toPolicyResponse(view)));
	}

	@PostMapping("/{id}/playback-sessions")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PlaybackSessionResponse>> issuePlaybackSession(@PathVariable UUID id,
			HttpServletRequest httpRequest) {
		String deviceFingerprintHash = RequestDeviceFingerprint.hash(httpRequest);
		PlaybackSessionView view = videoPlaybackSessionService.issuePlaybackSession(id, deviceFingerprintHash);
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(toSessionResponse(view)));
	}

	@PostMapping("/playback-sessions/{id}/progress")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> recordProgress(@PathVariable UUID id,
			@Valid @RequestBody PlaybackProgressRequest request, HttpServletRequest httpRequest) {
		String deviceFingerprintHash = RequestDeviceFingerprint.hash(httpRequest);
		videoPlaybackSessionService.recordProgress(id, request.playbackToken(), request.positionSeconds(),
				request.watchedDeltaSeconds(), deviceFingerprintHash);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	@PostMapping("/playback-sessions/{id}/end")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<Void>> endPlaybackSession(@PathVariable UUID id) {
		videoPlaybackSessionService.endPlaybackSession(id);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	private static VideoAssetResponse toResponse(VideoAssetView view) {
		return new VideoAssetResponse(view.id(), view.originalFilename(), view.mimeType(), view.sizeBytes(),
				view.durationSeconds(), view.status(), view.uploadedBy(), view.createdAt());
	}

	private static VideoPlaybackPolicyResponse toPolicyResponse(VideoPlaybackPolicyView view) {
		return new VideoPlaybackPolicyResponse(view.videoAssetId(), view.accessStartAt(), view.accessEndAt(),
				view.maxViewsPerStudent(), view.maxWatchDurationSeconds(), view.allowSeeking(), view.allowDownload(),
				view.watermarkEnabled(), view.maxConcurrentSessions());
	}

	private static PlaybackSessionResponse toSessionResponse(PlaybackSessionView view) {
		return new PlaybackSessionResponse(view.watchSessionId(), view.playbackToken(), view.signedUrl(),
				view.expiresAt(), view.watermarkText(), view.allowSeeking(), view.allowDownload());
	}

}
