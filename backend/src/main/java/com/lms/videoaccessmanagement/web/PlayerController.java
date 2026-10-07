package com.lms.videoaccessmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.videoaccessmanagement.service.player.PlayerExchangeService;
import com.lms.videoaccessmanagement.service.player.PlayerPairingService;
import com.lms.videoaccessmanagement.service.player.PlayerRequestVerifier;
import com.lms.videoaccessmanagement.service.player.PlayerSessionService;
import com.lms.videoaccessmanagement.web.dto.PlayerDtos;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * AOC Player endpoints (VIDEO Phase 8, ADR-025 + Amendment 1).
 *
 * <p>Student (JWT): start pairing, list and revoke own installations.
 *
 * <p>Player (NO JWT - the four exact carve-outs in {@code
 * SecurityFilterChainConfig} / {@code TenantResolutionFilter}): pairing
 * completion, exchange, heartbeat, end. Their bodies are read raw so the
 * installation signature covers the exact bytes received; the tenant is
 * resolved by the services from a server-held row, never from the request.
 */
@RestController
@RequestMapping("/api/v1/player")
public class PlayerController {

	static final String INSTALLATION_HEADER = "X-Player-Installation";

	static final String TIMESTAMP_HEADER = "X-Player-Timestamp";

	static final String NONCE_HEADER = "X-Player-Nonce";

	static final String SIGNATURE_HEADER = "X-Player-Signature";

	static final String TOKEN_HEADER = "X-Player-Token";

	private final PlayerPairingService pairingService;

	private final PlayerExchangeService exchangeService;

	private final PlayerSessionService sessionService;

	private final ObjectMapper objectMapper;

	public PlayerController(PlayerPairingService pairingService, PlayerExchangeService exchangeService,
			PlayerSessionService sessionService, ObjectMapper objectMapper) {
		this.pairingService = pairingService;
		this.exchangeService = exchangeService;
		this.sessionService = sessionService;
		this.objectMapper = objectMapper;
	}

	// ---- Student (JWT) ----

	@PostMapping("/pairings")
	public ResponseEntity<ApiResponse<PlayerDtos.PairingStartResponse>> startPairing() {
		PlayerPairingService.PairingStart start = pairingService.start();
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(new PlayerDtos.PairingStartResponse(start.pairingUrl(), start.expiresAt())));
	}

	@GetMapping("/installations")
	public ResponseEntity<ApiResponse<List<PlayerPairingService.InstallationView>>> listMine() {
		return ResponseEntity.ok(ApiResponse.success(pairingService.listMine()));
	}

	@PostMapping("/installations/{installationId}/revoke")
	public ResponseEntity<ApiResponse<PlayerPairingService.InstallationView>> revokeMine(
			@PathVariable UUID installationId) {
		return ResponseEntity.ok(ApiResponse.success(pairingService.revokeMine(installationId)));
	}

	// ---- AOC Player (no JWT) ----

	@PostMapping("/pairings/complete")
	public ResponseEntity<ApiResponse<PlayerDtos.PairingCompleteResponse>> completePairing(
			@RequestBody(required = false) byte[] body, HttpServletRequest request) {
		PlayerDtos.PairingCompleteRequest parsed = read(body, PlayerDtos.PairingCompleteRequest.class);
		UUID installationId = pairingService.complete(parsed == null ? null
				: new PlayerPairingService.PairingCompletion(parsed.pairingCode(), parsed.publicKey(), parsed.platform(),
						parsed.playerVersion()),
				request.getRemoteAddr());
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(new PlayerDtos.PairingCompleteResponse(installationId)));
	}

	@PostMapping("/exchange")
	public ResponseEntity<ApiResponse<PlayerDtos.ExchangeResponse>> exchange(
			@RequestBody(required = false) byte[] body,
			@RequestHeader(name = INSTALLATION_HEADER, required = false) String installation,
			HttpServletRequest request) {
		PlayerDtos.ExchangeRequest parsed = read(body, PlayerDtos.ExchangeRequest.class);
		PlayerExchangeService.ExchangeResult result = exchangeService.exchange(installation,
				parsed == null ? null
						: new PlayerExchangeService.ExchangeRequest(parsed.launchCode(), parsed.playerVersion(),
								parsed.platform()),
				signed(request, body), request.getRemoteAddr());
		return ResponseEntity.ok(ApiResponse.success(new PlayerDtos.ExchangeResponse(result.sessionId(),
				result.playbackToken(), result.expiresAt(), result.heartbeatIntervalSeconds(), result.descriptor())));
	}

	@PostMapping("/sessions/{sessionId}/heartbeat")
	public ResponseEntity<ApiResponse<PlayerDtos.HeartbeatResponse>> heartbeat(@PathVariable UUID sessionId,
			@RequestBody(required = false) byte[] body,
			@RequestHeader(name = TOKEN_HEADER, required = false) String token, HttpServletRequest request) {
		PlayerDtos.HeartbeatRequest parsed = read(body, PlayerDtos.HeartbeatRequest.class);
		PlayerSessionService.HeartbeatResult result = sessionService.heartbeat(sessionId, token,
				signed(request, body), parsed == null ? null : parsed.playerVersion());
		return ResponseEntity.ok(ApiResponse.success(new PlayerDtos.HeartbeatResponse(result.action(),
				result.reason(), result.playbackToken(), result.expiresAt(), result.heartbeatIntervalSeconds(),
				result.mediaUrl())));
	}

	@PostMapping("/sessions/{sessionId}/end")
	public ResponseEntity<ApiResponse<Void>> end(@PathVariable UUID sessionId,
			@RequestBody(required = false) byte[] body,
			@RequestHeader(name = TOKEN_HEADER, required = false) String token, HttpServletRequest request) {
		sessionService.end(sessionId, token, signed(request, body));
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	private static PlayerRequestVerifier.SignedRequest signed(HttpServletRequest request, byte[] body) {
		return new PlayerRequestVerifier.SignedRequest(request.getMethod(), request.getRequestURI(),
				request.getHeader(TIMESTAMP_HEADER), request.getHeader(NONCE_HEADER),
				request.getHeader(SIGNATURE_HEADER), body == null ? new byte[0] : body);
	}

	/** Malformed JSON reads as "no body"; the services then refuse with their generic error. */
	private <T> T read(byte[] body, Class<T> type) {
		if (body == null || body.length == 0 || body.length > 8192) {
			return null;
		}
		try {
			return objectMapper.readValue(body, type);
		}
		catch (JacksonException malformed) {
			return null;
		}
	}

}
