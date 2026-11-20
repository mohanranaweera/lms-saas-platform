package com.lms.videoaccessmanagement.web.dto;

import com.lms.videoaccessmanagement.domain.PlaybackState;
import com.lms.videoaccessmanagement.service.PlaybackDescriptor;
import java.time.Instant;
import java.util.UUID;

/** Request/response bodies for the AOC Player endpoints (VIDEO Phase 8, ADR-025 + Amendment 1). */
public final class PlayerDtos {

	private PlayerDtos() {
	}

	/** {@code POST /api/v1/player/pairings} (Student). */
	public record PairingStartResponse(String pairingUrl, Instant expiresAt) {
	}

	/** {@code POST /api/v1/player/pairings/complete} (player, no JWT). {@code publicKey}: base64 SPKI, P-256. */
	public record PairingCompleteRequest(String pairingCode, String publicKey, String platform, String playerVersion) {
	}

	public record PairingCompleteResponse(UUID installationId) {
	}

	/** {@code POST /api/v1/player/exchange} (player, signed). */
	public record ExchangeRequest(String launchCode, String playerVersion, String platform) {
	}

	public record ExchangeResponse(UUID sessionId, String playbackToken, Instant expiresAt,
			int heartbeatIntervalSeconds, PlaybackDescriptor descriptor) {
	}

	/** {@code POST /api/v1/player/sessions/{id}/heartbeat} (player, signed + token). */
	public record HeartbeatRequest(Integer positionSeconds, PlaybackState state, String playerVersion) {
	}

	/** {@code action} is CONTINUE or STOP; on STOP the player tears playback down. */
	public record HeartbeatResponse(String action, String reason, String playbackToken, Instant expiresAt,
			Integer heartbeatIntervalSeconds, String mediaUrl, Integer resumePositionSeconds,
			Integer remainingWatchSeconds) {
	}

	/** Admin revocations; the reason is required and audited. */
	public record RevokeRequest(String reason) {
	}

	/** {@code POST /api/v1/playback/sessions}: WEB carries a descriptor, NATIVE a launch link. */
	public record PlaybackStartResponse(UUID sessionId, String channel, Instant expiresAt,
			PlaybackDescriptor descriptor, Launch launch) {
	}

	public record Launch(String url, Instant expiresAt) {
	}

	/** {@code GET /api/v1/playback/sessions/{id}/status} (the Student's own NATIVE session). */
	public record LaunchStatusResponse(String status, boolean exchanged) {
	}

}
