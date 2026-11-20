package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.videoaccessmanagement.service.player.PlayerPairingService;
import com.lms.videoaccessmanagement.service.player.PlayerSessionService;
import com.lms.videoaccessmanagement.web.dto.PlayerDtos;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff management of AOC Player installations and live NATIVE sessions (VIDEO
 * Phase 8), next to the ADR-022 device endpoints ({@code
 * /api/v1/students/{studentId}/devices}). {@code DomainArea.DEVICES}: VIEW to
 * list, CREATE_EDIT to revoke (with an audited reason). Ids from another
 * tenant are 404.
 */
@RestController
public class StudentPlayerInstallationController {

	private final PlayerPairingService pairingService;

	private final PlayerSessionService sessionService;

	public StudentPlayerInstallationController(PlayerPairingService pairingService,
			PlayerSessionService sessionService) {
		this.pairingService = pairingService;
		this.sessionService = sessionService;
	}

	@GetMapping("/api/v1/students/{studentId}/player-installations")
	public ResponseEntity<ApiResponse<List<PlayerPairingService.InstallationView>>> list(@PathVariable UUID studentId) {
		return ResponseEntity.ok(ApiResponse.success(pairingService.listForStudent(studentId)));
	}

	@PostMapping("/api/v1/students/{studentId}/player-installations/{installationId}/revoke")
	public ResponseEntity<ApiResponse<PlayerPairingService.InstallationView>> revoke(@PathVariable UUID studentId,
			@PathVariable UUID installationId, @RequestBody(required = false) PlayerDtos.RevokeRequest request) {
		return ResponseEntity.ok(ApiResponse.success(pairingService.revokeForStudent(studentId, installationId,
				request == null ? null : request.reason())));
	}

	@PostMapping("/api/v1/playback/sessions/{sessionId}/revoke")
	public ResponseEntity<ApiResponse<Void>> revokeSession(@PathVariable UUID sessionId,
			@RequestBody(required = false) PlayerDtos.RevokeRequest request) {
		sessionService.adminRevoke(sessionId, request == null ? null : request.reason());
		return ResponseEntity.ok(ApiResponse.success(null));
	}

}
