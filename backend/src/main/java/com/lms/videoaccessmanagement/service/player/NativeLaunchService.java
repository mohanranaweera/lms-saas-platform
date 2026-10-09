package com.lms.videoaccessmanagement.service.player;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PlayerRequestException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DeviceAccessApi;
import com.lms.integrationmanagement.api.ObjectStorageApi;
import com.lms.integrationmanagement.api.SignedDownloadUrl;
import com.lms.videoaccessmanagement.config.PlayerProperties;
import com.lms.videoaccessmanagement.domain.LaunchChannel;
import com.lms.videoaccessmanagement.domain.PlaybackLaunchCode;
import com.lms.videoaccessmanagement.domain.PlayerPairingCode;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoWatchSession;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionRevokedReason;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionStatus;
import com.lms.videoaccessmanagement.repository.PlaybackLaunchCodeRepository;
import com.lms.videoaccessmanagement.repository.PlayerInstallationRepository;
import com.lms.videoaccessmanagement.repository.PlayerPairingCodeRepository;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchSessionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starts NATIVE playback for the signed-in Student (VIDEO Phase 8, ADR-025 §2):
 * a PENDING_LAUNCH session bound to the browser's ADR-022 device plus a
 * one-time launch code the browser hands to the AOC Player as
 * {@code aocplayer://play?code=}. When the Student has no ACTIVE player on
 * this device, the link also carries a one-time pairing code
 * ({@code &pair=}), so the player pairs itself and plays in one step
 * (ADR-025 Amendment 2). Pressing Play again supersedes any earlier
 * pending launch for the same video. An unclaimed launch simply expires with
 * its code; the browser learns the outcome only from {@link #status}.
 */
@Service
public class NativeLaunchService {

	public static final Duration INTERNAL_MEDIA_URL_TTL = Duration.ofMinutes(3);

	private static final int MAX_PAIRING_TTL_SECONDS = 120;

	private final VideoWatchSessionRepository sessions;

	private final PlaybackLaunchCodeRepository launchCodes;

	private final PlayerSessionRevoker revoker;

	private final PlayerCodes codes;

	private final DeviceAccessApi deviceAccessApi;

	private final TenantContext tenantContext;

	private final VideoAssetRepository videoAssets;

	private final ObjectStorageApi objectStorageApi;

	private final PlayerInstallationRepository installations;

	private final PlayerPairingCodeRepository pairingCodes;

	private final PlayerProperties properties;

	public NativeLaunchService(VideoWatchSessionRepository sessions, PlaybackLaunchCodeRepository launchCodes,
			PlayerSessionRevoker revoker, PlayerCodes codes, DeviceAccessApi deviceAccessApi,
			TenantContext tenantContext, VideoAssetRepository videoAssets, ObjectStorageApi objectStorageApi,
			PlayerInstallationRepository installations, PlayerPairingCodeRepository pairingCodes,
			PlayerProperties properties) {
		this.installations = installations;
		this.pairingCodes = pairingCodes;
		this.properties = properties;
		this.sessions = sessions;
		this.launchCodes = launchCodes;
		this.revoker = revoker;
		this.codes = codes;
		this.deviceAccessApi = deviceAccessApi;
		this.tenantContext = tenantContext;
		this.videoAssets = videoAssets;
		this.objectStorageApi = objectStorageApi;
	}

	public record NativeLaunch(UUID sessionId, String url, Instant expiresAt) {
	}

	public record LaunchStatus(String status, boolean exchanged) {
	}

	@Transactional
	public NativeLaunch start(UUID materialId, UUID videoAssetId, int launchCodeTtlSeconds) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		UUID deviceId = requireCurrentDevice(principal);
		UUID tenantId = tenantContext.getTenantId();
		Instant now = Instant.now();
		for (VideoWatchSession pending : sessions.findPendingNativeFor(tenantId, videoAssetId, principal.userId())) {
			revoker.revoke(pending, VideoWatchSessionRevokedReason.SUPERSEDED_BY_NEW_SESSION, now);
		}
		String code = codes.newCode();
		Instant expiresAt = now.plusSeconds(launchCodeTtlSeconds);
		VideoWatchSession session = sessions.save(VideoWatchSession.pendingNative(tenantId, videoAssetId,
				principal.userId(), materialId, deviceId, UUID.randomUUID(), now, expiresAt));
		launchCodes.save(new PlaybackLaunchCode(tenantId, session.getId(), principal.userId(), deviceId,
				codes.hash(code), now, expiresAt));
		String url = "aocplayer://play?code=" + code;
		if (!hasPairedPlayer(principal.userId(), deviceId)) {
			url += "&pair=" + issuePairingCode(tenantId, principal.userId(), deviceId, now).code();
		}
		return new NativeLaunch(session.getId(), url, expiresAt);
	}

	/**
	 * The signed-in Student's own NATIVE session (anyone else's, or a foreign
	 * id, is a 404). An unclaimed launch past its code's expiry reads EXPIRED.
	 */
	@Transactional(readOnly = true)
	public LaunchStatus status(UUID sessionId) {
		UUID studentId = AuthenticatedPrincipalHolder.get().userId();
		VideoWatchSession session = sessions.findById(sessionId)
			.filter(found -> found.getStudentId().equals(studentId) && found.getLaunchChannel() == LaunchChannel.NATIVE)
			.orElseThrow(() -> new NotFoundException("Playback session not found"));
		String status = session.getStatus().name();
		if (session.getStatus() == VideoWatchSessionStatus.PENDING_LAUNCH
				&& Instant.now().isAfter(session.getExpiresAt())) {
			status = "EXPIRED";
		}
		return new LaunchStatus(status, session.getPlayerInstallationId() != null);
	}

	/**
	 * A short-lived signed URL for a platform-storage (INTERNAL) asset, for the
	 * AOC Player. Presigning is a local signature, not a network call.
	 */
	public SignedDownloadUrl signInternalMedia(UUID videoAssetId) {
		VideoAsset asset = videoAssets.findById(videoAssetId)
			.orElseThrow(() -> new NotFoundException("Lesson media not found"));
		return objectStorageApi.generateSignedDownloadUrl(asset.getStorageObjectKey(), INTERNAL_MEDIA_URL_TTL);
	}

	/** The ACTIVE ADR-022 device behind the caller's browser session (409 when there is none). */
	UUID requireCurrentDevice(AuthenticatedPrincipal principal) {
		if (principal.sessionId() == null) {
			throw deviceNotRegistered();
		}
		return deviceAccessApi.findActiveSessionDeviceId(principal.userId(), principal.sessionId())
			.orElseThrow(NativeLaunchService::deviceNotRegistered);
	}

	/** A one-time pairing code (plaintext) and when it expires. */
	public record IssuedPairingCode(String code, Instant expiresAt) {
	}

	/**
	 * Issues a pairing code bound to the Student's device (stored hashed only).
	 * Used by the pairing page and by a launch for a device without a player.
	 */
	IssuedPairingCode issuePairingCode(UUID tenantId, UUID userId, UUID deviceId, Instant now) {
		Instant expiresAt = now.plusSeconds(Math.min(properties.getPairingCodeTtlSeconds(), MAX_PAIRING_TTL_SECONDS));
		String code = codes.newCode();
		pairingCodes.save(new PlayerPairingCode(tenantId, userId, deviceId, codes.hash(code), now, expiresAt));
		return new IssuedPairingCode(code, expiresAt);
	}

	/**
	 * A launch code can only be claimed by an ACTIVE installation of this
	 * Student on this device; without one the launch also carries a pairing
	 * code (e.g. first use, or the device was removed while the player was closed).
	 */
	private boolean hasPairedPlayer(UUID userId, UUID deviceId) {
		return installations.findActiveForDevice(deviceId)
			.filter(installation -> installation.getUserId().equals(userId))
			.isPresent();
	}

	private static PlayerRequestException deviceNotRegistered() {
		return new PlayerRequestException(HttpStatus.CONFLICT, ApiErrorCodes.PLAYER_DEVICE_NOT_REGISTERED,
				"Sign in again on this device to use the AOC Player.");
	}

}
