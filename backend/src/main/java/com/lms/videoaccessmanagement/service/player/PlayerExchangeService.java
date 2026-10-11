package com.lms.videoaccessmanagement.service.player;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.PlayerRequestException;
import com.lms.common.logging.ContactMasking;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.videoaccessmanagement.service.PlaybackMediaSigner.SignedMedia;
import com.lms.usermanagement.api.StudentContact;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.identityaccessservice.api.TenantUserSummary;
import com.lms.identityaccessservice.api.UserProvisioningApi;
import com.lms.videoaccessmanagement.domain.TenantVideoSettings;
import com.lms.videoaccessmanagement.domain.WatermarkField;
import com.lms.videoaccessmanagement.service.LessonMediaPlayability;
import com.lms.videoaccessmanagement.service.PlaybackDescriptor;
import com.lms.videoaccessmanagement.service.PlaybackDescriptor.CaptureProtectionPolicy;
import com.lms.videoaccessmanagement.service.PlaybackDescriptor.WatermarkPolicy;
import com.lms.videoaccessmanagement.service.PlaybackDescriptor.WatermarkPolicy.Placement;
import com.lms.videoaccessmanagement.service.PlaybackTokenService;
import com.lms.videoaccessmanagement.service.TenantVideoConfigurationService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

	private final TenantVideoConfigurationService configurationService;

	private final UserProvisioningApi userProvisioningApi;

	private final StudentLookupApi studentLookupApi;

	public PlayerExchangeService(PlayerExchangeTransactions transactions, PlayerCodes codes,
			PlayerRateLimiter rateLimiter, VideoFeatureFlags featureFlags, PlaybackTokenService tokenService,
			NativeLaunchService nativeLaunchService, TenantVideoConfigurationService configurationService,
			UserProvisioningApi userProvisioningApi, StudentLookupApi studentLookupApi) {
		this.transactions = transactions;
		this.codes = codes;
		this.rateLimiter = rateLimiter;
		this.featureFlags = featureFlags;
		this.tokenService = tokenService;
		this.nativeLaunchService = nativeLaunchService;
		this.configurationService = configurationService;
		this.userProvisioningApi = userProvisioningApi;
		this.studentLookupApi = studentLookupApi;
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
		TenantVideoSettings settings = configurationService.effectiveSettings();
		WatermarkPolicy watermark = watermarkFor(settings, claimed);
		CaptureProtectionPolicy capture = captureProtectionFor(settings);
		if (playable.isYouTube()) {
			return new PlaybackDescriptor(claimed.sessionId(), MediaAssetProvider.YOUTUBE,
					playable.asset().providerAssetId(), null, claimed.expiresAt(), playable.asset().title(),
					playable.asset().durationSeconds(), watermark.text(), true, false, watermark, capture);
		}
		SignedMedia media = nativeLaunchService.signMedia(playable.asset().id());
		return new PlaybackDescriptor(claimed.sessionId(), MediaAssetProvider.valueOf(playable.asset().provider()),
				null, media.url(), media.expiresAt(), playable.asset().title(), playable.asset().durationSeconds(),
				watermark.text(), true, false, watermark, capture, media.format().name(), media.embed());
	}

	private WatermarkPolicy watermarkFor(TenantVideoSettings settings,
			PlayerExchangeTransactions.Claimed claimed) {
		// ADR-025 Amendment 4: YouTube gets the overlay too (owner-accepted terms risk).
		Placement placement = Placement.OVERLAY;
		boolean enabled = settings.watermarkEnabled() && featureFlags.isDynamicWatermark();
		if (!enabled) {
			return new WatermarkPolicy(false, null, null, 0, placement);
		}
		// Both values come from the trusted, tenant-scoped identity API. The player
		// request cannot supply or alter this attribution text.
		String studentDisplayId = studentDisplayId(claimed.studentId());
		String maskedEmail = null;
		if (settings.watermarkFields().contains(WatermarkField.MASKED_EMAIL)) {
			TenantUserSummary student = userProvisioningApi.findTenantUserSummaries(List.of(claimed.studentId()))
				.stream().findFirst().orElse(null);
			maskedEmail = student == null ? null : student.maskedEmail();
		}
		StudentContact contact = null;
		if (settings.watermarkFields().contains(WatermarkField.STUDENT_FULL_NAME)
				|| settings.watermarkFields().contains(WatermarkField.MASKED_PHONE)) {
			contact = studentLookupApi.findContact(claimed.studentId()).orElse(null);
		}
		String maskedPhone = contact == null ? null : ContactMasking.maskPhone(contact.mobile());
		StringBuilder text = new StringBuilder();
		if (settings.watermarkFields().contains(WatermarkField.STUDENT_DISPLAY_ID)) {
			append(text, "Student ID: ", studentDisplayId);
		}
		if (settings.watermarkFields().contains(WatermarkField.STUDENT_FULL_NAME)) {
			append(text, "Student name: ", contact == null ? null : contact.name());
		}
		if (settings.watermarkFields().contains(WatermarkField.MASKED_EMAIL) && maskedEmail != null) {
			append(text, "Email: ", maskedEmail);
		}
		if (settings.watermarkFields().contains(WatermarkField.MASKED_PHONE) && maskedPhone != null) {
			append(text, "Phone: ", maskedPhone);
		}
		if (settings.watermarkFields().contains(WatermarkField.SESSION_ID)) {
			append(text, "Session: ", claimed.sessionId().toString().substring(0, 8));
		}
		if (settings.watermarkFields().contains(WatermarkField.TIMESTAMP)) {
			append(text, "Date and time: ", Instant.now().toString());
		}
		if (text.length() == 0) append(text, "Session: ", claimed.sessionId().toString().substring(0, 8));
		return new WatermarkPolicy(true, text.toString(), settings.watermarkOpacity(),
				settings.watermarkMovementIntervalSeconds(), placement);
	}

	private static void append(StringBuilder text, String label, String value) {
		if (value == null || value.isBlank()) return;
		if (text.length() > 0) text.append('\n');
		text.append(label).append(value);
	}

	private static String studentDisplayId(UUID userId) {
		return userId.toString().substring(0, 8);
	}

	private CaptureProtectionPolicy captureProtectionFor(TenantVideoSettings settings) {
		boolean enabled = settings.captureProtectionEnabled() && featureFlags.isCaptureProtection();
		// Keep the tenant's required bit even when the platform gate is off. The
		// native player then fails closed instead of silently playing unprotected.
		return new CaptureProtectionPolicy(enabled, settings.captureProtectionRequired());
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
