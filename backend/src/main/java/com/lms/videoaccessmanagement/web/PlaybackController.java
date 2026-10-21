package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.videoaccessmanagement.service.PlaybackAuthorizationService;
import com.lms.videoaccessmanagement.service.player.NativeLaunchService;
import com.lms.videoaccessmanagement.web.dto.PlayerDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Student playback authorization (VIDEO Phase 7 WEB, Phase 8 NATIVE).
 * {@code POST /sessions} answers a WEB descriptor or a NATIVE launch link
 * ({@code channel}); {@code GET /sessions/{id}/status} lets the browser see
 * whether the AOC Player claimed its launch. Both are the Student's own only.
 */
@RestController
@RequestMapping("/api/v1/playback")
@Validated
public class PlaybackController {

	private final PlaybackAuthorizationService authorizationService;

	private final NativeLaunchService nativeLaunchService;

	public PlaybackController(PlaybackAuthorizationService authorizationService,
			NativeLaunchService nativeLaunchService) {
		this.authorizationService = authorizationService;
		this.nativeLaunchService = nativeLaunchService;
	}

	@PostMapping("/sessions")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PlayerDtos.PlaybackStartResponse>> create(
			@Valid @RequestBody CreatePlaybackRequest request) {
		PlaybackAuthorizationService.PlaybackStart start = authorizationService.authorize(request.lessonMediaId());
		PlayerDtos.Launch launch = start.launch() == null ? null
				: new PlayerDtos.Launch(start.launch().url(), start.launch().expiresAt());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(new PlayerDtos.PlaybackStartResponse(
				start.sessionId(), start.channel().name(), start.expiresAt(), start.descriptor(), launch)));
	}

	@GetMapping("/sessions/{sessionId}/status")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<PlayerDtos.LaunchStatusResponse>> status(@PathVariable UUID sessionId) {
		NativeLaunchService.LaunchStatus status = nativeLaunchService.status(sessionId);
		return ResponseEntity.ok(ApiResponse.success(new PlayerDtos.LaunchStatusResponse(status.status(),
				status.exchanged())));
	}

	public record CreatePlaybackRequest(@NotNull UUID lessonMediaId) {
	}

}
