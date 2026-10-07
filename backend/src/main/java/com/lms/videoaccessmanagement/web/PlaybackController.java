package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.videoaccessmanagement.service.PlaybackAuthorizationService;
import com.lms.videoaccessmanagement.service.PlaybackDescriptor;
import com.lms.videoaccessmanagement.web.dto.WebPlaybackSessionResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated WEB playback authorization (Phase 7, B3-only; no native exchange). */
@RestController
@RequestMapping("/api/v1/playback")
@Validated
public class PlaybackController {

	private final PlaybackAuthorizationService authorizationService;

	public PlaybackController(PlaybackAuthorizationService authorizationService) {
		this.authorizationService = authorizationService;
	}

	@PostMapping("/sessions")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<ApiResponse<WebPlaybackSessionResponse>> create(@Valid @RequestBody CreatePlaybackRequest request) {
		PlaybackDescriptor descriptor = authorizationService.authorize(request.lessonMediaId());
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
				new WebPlaybackSessionResponse(descriptor.sessionId(), descriptor.expiresAt(), descriptor)));
	}

	public record CreatePlaybackRequest(@NotNull UUID lessonMediaId) {
	}

}
