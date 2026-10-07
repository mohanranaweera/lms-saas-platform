package com.lms.videoaccessmanagement.service.player;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.PlayerRequestException;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.service.LessonMediaPlayability;
import com.lms.videoaccessmanagement.service.PlaybackDescriptor;
import com.lms.videoaccessmanagement.service.PlaybackTokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * {@code POST /api/v1/player/exchange} (VIDEO Phase 8, ADR-025 §4, no JWT):
 * a paired AOC Player trades a one-time launch code for its playback session.
 * Rate limited per IP and per installation. Every refusal is the same generic
 * {@code 400 PLAYBACK_LAUNCH_CODE_INVALID} (VID-ADR-008; the reason goes to the
 * security log), except an old player, which gets 426.
 */
@Service
public class PlayerExchangeService {

	private static final Logger log = LoggerFactory.getLogger(PlayerExchangeService.class);

	private final PlayerExchangeTransactions transactions;

	private final PlayerCodes codes;

	private final PlayerRateLimiter rateLimiter;

	private final VideoFeatureFlags featureFlags;

	private final PlaybackTokenService tokenService;

	private final NativeLaunchService nativeLaunchService;

	public PlayerExchangeService(PlayerExchangeTransactions transactions, PlayerCodes codes,
			PlayerRateLimiter rateLimiter, VideoFeatureFlags featureFlags, PlaybackTokenService tokenService,
			NativeLaunchService nativeLaunchService) {
		this.transactions = transactions;
		this.codes = codes;
		this.rateLimiter = rateLimiter;
		this.featureFlags = featureFlags;
		this.tokenService = tokenService;
		this.nativeLaunchService = nativeLaunchService;
	}

	public record ExchangeRequest(String launchCode, String playerVersion, String platform) {
	}

	public record ExchangeResult(UUID sessionId, String playbackToken, Instant expiresAt,
			int heartbeatIntervalSeconds, PlaybackDescriptor descriptor) {
	}

	public ExchangeResult exchange(String installationHeader, ExchangeRequest request,
			PlayerRequestVerifier.SignedRequest signed, String clientIp) {
		if (!rateLimiter.tryAcquire("exchange:ip:" + clientIp, 30, Duration.ofMinutes(1))) {
			throw rateLimited();
		}
		if (!featureFlags.isNativePlayer()) {
			throw refused("native_player_disabled");
		}
		UUID installationId = parseUuid(installationHeader);
		if (installationId == null) {
			throw refused("malformed_installation");
		}
		// Keyed by installation AND IP: this runs before the signature check, so an
		// installation-only key would let anyone lock a real player out for a minute.
		if (!rateLimiter.tryAcquire("exchange:installation:" + installationId + ":" + clientIp, 20,
				Duration.ofMinutes(1))) {
			throw rateLimited();
		}
		if (request == null || !codes.isWellFormed(request.launchCode())) {
			throw refused("malformed_code");
		}
		PlayerExchangeTransactions.Outcome outcome = transactions.claim(codes.hash(request.launchCode()),
				installationId, signed, request.playerVersion());
		if (outcome instanceof PlayerExchangeTransactions.Refused refusal) {
			if (refusal.versionUnsupported()) {
				throw PlayerPairingService.versionUnsupported();
			}
			throw refused(refusal.reason());
		}
		PlayerExchangeTransactions.Claimed claimed = (PlayerExchangeTransactions.Claimed) outcome;
		TenantContextHolder.set(claimed.tenantId());
		try {
			PlaybackDescriptor descriptor = descriptorFor(claimed);
			String token = tokenService.issuePlayerToken(claimed.studentId(), claimed.tenantId(),
					claimed.playable().asset().id(), claimed.sessionId(), claimed.installationId(), claimed.jti(),
					Instant.now(), claimed.expiresAt());
			return new ExchangeResult(claimed.sessionId(), token, claimed.expiresAt(),
					claimed.heartbeatIntervalSeconds(), descriptor);
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	private PlaybackDescriptor descriptorFor(PlayerExchangeTransactions.Claimed claimed) {
		LessonMediaPlayability.Playable playable = claimed.playable();
		if (playable.isYouTube()) {
			return new PlaybackDescriptor(claimed.sessionId(), MediaAssetProvider.YOUTUBE,
					playable.asset().providerAssetId(), null, claimed.expiresAt(), playable.asset().title(),
					playable.asset().durationSeconds(), null, true, false);
		}
		SignedDownloadUrl media = nativeLaunchService.signInternalMedia(playable.asset().id());
		return new PlaybackDescriptor(claimed.sessionId(), MediaAssetProvider.INTERNAL, null, media.url(),
				media.expiresAt(), playable.asset().title(), playable.asset().durationSeconds(), null, true, false);
	}

	private static UUID parseUuid(String value) {
		try {
			return value == null ? null : UUID.fromString(value);
		}
		catch (IllegalArgumentException malformed) {
			return null;
		}
	}

	private static PlayerRequestException rateLimited() {
		return new PlayerRequestException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCodes.PLAYER_RATE_LIMITED,
				"Too many attempts. Wait a minute and try again.");
	}

	private static PlayerRequestException refused(String reason) {
		log.atWarn().setMessage("player.exchange_refused").addKeyValue("reason", reason).log();
		return new PlayerRequestException(HttpStatus.BAD_REQUEST, ApiErrorCodes.PLAYBACK_LAUNCH_CODE_INVALID,
				"This launch link is invalid or has expired. Press Play again in your browser.");
	}

}
