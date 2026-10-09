package com.lms.videoaccessmanagement.service.player;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.PlaybackNotAuthorizedException;
import com.lms.common.error.PlayerRequestException;
import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DeviceAccessApi;
import com.lms.identityaccessservice.api.DeviceRevokedEvent;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.videoaccessmanagement.config.PlayerProperties;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.domain.PlayerInstallation;
import com.lms.videoaccessmanagement.domain.PlayerPairingCode;
import com.lms.videoaccessmanagement.domain.PlayerRevocationSource;
import com.lms.videoaccessmanagement.domain.VideoWatchSessionRevokedReason;
import com.lms.videoaccessmanagement.repository.PlayerInstallationRepository;
import com.lms.videoaccessmanagement.repository.PlayerPairingCodeRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Pairs and revokes AOC Player installations (VIDEO Phase 8, ADR-025 §3 +
 * Amendment 1).
 *
 * <p>Pairing: a signed-in Student on a registered device gets a one-time code
 * ({@link #start}); the player posts it with its public key to the
 * unauthenticated {@link #complete}, whose tenant, user and device come ONLY
 * from the consumed code row. The installation shares that device's slot
 * (Q-008); re-pairing the same device replaces the previous installation.
 * Revoking an installation - by the Student, an admin, or because its device
 * was revoked - ends its live playback in the same transaction.
 */
@Service
public class PlayerPairingService {

	private static final Logger log = LoggerFactory.getLogger(PlayerPairingService.class);

	private static final String STUDENT_ROLE = "STUDENT";

	private static final String WINDOWS = "WINDOWS";


	private final PlayerPairingCodeRepository pairingCodes;

	private final PlayerInstallationRepository installations;

	private final PlayerSessionRevoker revoker;

	private final PlayerCodes codes;

	private final PlayerProperties properties;

	private final DeviceAccessApi deviceAccessApi;

	private final VideoFeatureFlags featureFlags;

	private final PlayerRateLimiter rateLimiter;

	private final AuditLogApi auditLogApi;

	private final PermissionCheckService permissionCheckService;

	private final StudentLookupApi studentLookupApi;

	private final TenantContext tenantContext;

	private final NativeLaunchService nativeLaunchService;

	public PlayerPairingService(PlayerPairingCodeRepository pairingCodes, PlayerInstallationRepository installations,
			PlayerSessionRevoker revoker, PlayerCodes codes, PlayerProperties properties,
			DeviceAccessApi deviceAccessApi, VideoFeatureFlags featureFlags, PlayerRateLimiter rateLimiter,
			AuditLogApi auditLogApi, PermissionCheckService permissionCheckService,
			StudentLookupApi studentLookupApi, TenantContext tenantContext,
			NativeLaunchService nativeLaunchService) {
		this.pairingCodes = pairingCodes;
		this.installations = installations;
		this.revoker = revoker;
		this.codes = codes;
		this.properties = properties;
		this.deviceAccessApi = deviceAccessApi;
		this.featureFlags = featureFlags;
		this.rateLimiter = rateLimiter;
		this.auditLogApi = auditLogApi;
		this.permissionCheckService = permissionCheckService;
		this.studentLookupApi = studentLookupApi;
		this.tenantContext = tenantContext;
		this.nativeLaunchService = nativeLaunchService;
	}

	public record PairingStart(String pairingUrl, Instant expiresAt) {
	}

	public record PairingCompletion(String pairingCode, String publicKey, String platform, String playerVersion) {
	}

	public record InstallationView(UUID id, UUID deviceId, String platform, String playerVersion, String status,
			Instant pairedAt, Instant lastSeenAt, Instant revokedAt, String revocationSource) {

		static InstallationView of(PlayerInstallation installation) {
			return new InstallationView(installation.getId(), installation.getDeviceId(), installation.getPlatform(),
					installation.getPlayerVersion(), installation.getStatus().name(), installation.getPairedAt(),
					installation.getLastSeenAt(), installation.getRevokedAt(),
					installation.getRevocationSource() == null ? null : installation.getRevocationSource().name());
		}

	}

	// ---- Student (JWT) ----

	@Transactional
	public PairingStart start() {
		AuthenticatedPrincipal principal = requireStudent();
		requireNativePlayerEnabled();
		UUID deviceId = nativeLaunchService.requireCurrentDevice(principal);
		NativeLaunchService.IssuedPairingCode issued = nativeLaunchService.issuePairingCode(
				tenantContext.getTenantId(), principal.userId(), deviceId, Instant.now());
		return new PairingStart("aocplayer://pair?code=" + issued.code(), issued.expiresAt());
	}

	@Transactional(readOnly = true)
	public List<InstallationView> listMine() {
		AuthenticatedPrincipal principal = requireStudent();
		return installations.findAllForUser(principal.userId()).stream().map(InstallationView::of).toList();
	}

	@Transactional
	public InstallationView revokeMine(UUID installationId) {
		AuthenticatedPrincipal principal = requireStudent();
		PlayerInstallation installation = installations.findForUser(installationId, principal.userId())
			.orElseThrow(() -> new NotFoundException("AOC Player not found"));
		revokeWithAudit(installation, principal.userId(), PlayerRevocationSource.STUDENT, "Removed by the student");
		return InstallationView.of(installation);
	}

	// ---- Tenant Admin (JWT, DEVICES) ----

	/** @param studentProfileId the student profile id, as in {@code /api/v1/students/{studentId}/devices}. */
	@Transactional(readOnly = true)
	public List<InstallationView> listForStudent(UUID studentProfileId) {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.VIEW);
		UUID userId = requireStudentUserId(studentProfileId);
		return installations.findAllForUser(userId).stream().map(InstallationView::of).toList();
	}

	@Transactional
	public InstallationView revokeForStudent(UUID studentProfileId, UUID installationId, String reason) {
		permissionCheckService.requirePermission(DomainArea.DEVICES, PermissionAction.CREATE_EDIT);
		String checkedReason = requireReason(reason);
		UUID userId = requireStudentUserId(studentProfileId);
		PlayerInstallation installation = installations.findForUser(installationId, userId)
			.orElseThrow(() -> new NotFoundException("AOC Player not found"));
		revokeWithAudit(installation, AuthenticatedPrincipalHolder.get().userId(), PlayerRevocationSource.ADMIN,
				checkedReason);
		return InstallationView.of(installation);
	}

	// ---- AOC Player (no JWT; ADR-025 Amendment 1) ----

	/**
	 * Consumes a pairing code and registers the player's public key. Any
	 * failure is the same generic 400 (the reason goes to the security log);
	 * an old player gets 426. Nothing is written unless pairing succeeds.
	 */
	@Transactional
	public UUID complete(PairingCompletion request, String clientIp) {
		if (!rateLimiter.tryAcquire("pair:ip:" + clientIp, 20, Duration.ofMinutes(1))) {
			throw new PlayerRequestException(HttpStatus.TOO_MANY_REQUESTS, ApiErrorCodes.PLAYER_RATE_LIMITED,
					"Too many attempts. Wait a minute and try again.");
		}
		if (!featureFlags.isNativePlayer()) {
			throw refused("native_player_disabled");
		}
		if (request == null || !codes.isWellFormed(request.pairingCode())) {
			throw refused("malformed_code");
		}
		PlayerPairingCode code = pairingCodes.findByCodeHashAcrossTenantsForUpdate(codes.hash(request.pairingCode()))
			.orElseThrow(() -> refused("unknown_code"));
		// ADR-025 Amendment 1: the tenant comes only from the consumed code row.
		TenantContextHolder.set(code.getTenantId());
		try {
			Instant now = Instant.now();
			if (!code.isUsableAt(now)) {
				throw refused("code_not_usable");
			}
			if (!WINDOWS.equals(request.platform())) {
				throw refused("unsupported_platform");
			}
			if (!PlayerVersion.isAtLeast(request.playerVersion(), properties.getMinVersion())) {
				throw versionUnsupported();
			}
			if (PlayerRequestVerifier.parseP256PublicKey(request.publicKey()).isEmpty()) {
				throw refused("bad_public_key");
			}
			if (!deviceAccessApi.isDeviceAuthorized(code.getUserId(), code.getDeviceId(), null)) {
				throw refused("device_not_active");
			}
			installations.findActiveForDevice(code.getDeviceId())
				.ifPresent(previous -> revoke(previous, code.getUserId(), PlayerRevocationSource.REPAIRED,
						"Replaced by a new pairing", now));
			PlayerInstallation installation = installations.saveAndFlush(new PlayerInstallation(code.getTenantId(),
					code.getUserId(), code.getDeviceId(), request.publicKey(), WINDOWS, request.playerVersion(), now));
			code.consume(installation.getId(), now);
			pairingCodes.saveAndFlush(code);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("deviceId", code.getDeviceId().toString());
			metadata.put("platform", WINDOWS);
			metadata.put("playerVersion", request.playerVersion());
			auditLogApi.record(new AuditLogEntry(code.getUserId(), "player.paired", "player_installation",
					installation.getId(), null, metadata));
			return installation.getId();
		}
		finally {
			TenantContextHolder.clear();
		}
	}

	// ---- device revoked (identity event, same transaction) ----

	@EventListener
	public void onDeviceRevoked(DeviceRevokedEvent event) {
		Instant now = event.revokedAt();
		installations.findActiveForDevice(event.deviceId())
			.ifPresent(installation -> revoke(installation, event.actorId(), PlayerRevocationSource.DEVICE_REVOKED,
					"Device removed", now));
		revoker.revokeForDevice(event.tenantId(), event.deviceId(), VideoWatchSessionRevokedReason.DEVICE_REVOKED, now);
	}

	// ---- internals ----

	private void revokeWithAudit(PlayerInstallation installation, UUID actorId, PlayerRevocationSource source,
			String reason) {
		if (!installation.isActive()) {
			throw new ConflictException("This AOC Player has already been removed.");
		}
		revoke(installation, actorId, source, reason, Instant.now());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("userId", installation.getUserId().toString());
		metadata.put("deviceId", installation.getDeviceId().toString());
		metadata.put("source", source.name());
		metadata.put("before", "active");
		metadata.put("after", "revoked");
		auditLogApi.record(new AuditLogEntry(actorId, "player.installation_revoked", "player_installation",
				installation.getId(), reason, metadata));
	}

	private void revoke(PlayerInstallation installation, UUID actorId, PlayerRevocationSource source, String reason,
			Instant now) {
		installation.revoke(actorId, source, reason, now);
		installations.saveAndFlush(installation);
		VideoWatchSessionRevokedReason sessionReason = source == PlayerRevocationSource.DEVICE_REVOKED
				? VideoWatchSessionRevokedReason.DEVICE_REVOKED : VideoWatchSessionRevokedReason.INSTALLATION_REVOKED;
		revoker.revokeForInstallation(installation.getTenantId(), installation.getId(), sessionReason, now);
	}

	private AuthenticatedPrincipal requireStudent() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!STUDENT_ROLE.equals(principal.role())) {
			throw new PlaybackNotAuthorizedException("Only students can use the AOC Player.");
		}
		return principal;
	}

	/** Tenant-scoped: a profile id from another tenant is a 404. */
	private UUID requireStudentUserId(UUID studentProfileId) {
		return studentLookupApi.resolveUserId(studentProfileId)
			.orElseThrow(() -> new NotFoundException("Student not found"));
	}

	private void requireNativePlayerEnabled() {
		if (!featureFlags.isNativePlayer()) {
			throw new PlayerRequestException(HttpStatus.CONFLICT, ApiErrorCodes.NATIVE_PLAYER_UNAVAILABLE,
					"The AOC Player isn't available right now.");
		}
	}

	private static String requireReason(String reason) {
		if (!StringUtils.hasText(reason) || reason.trim().length() > 500) {
			throw new FieldValidationException("Enter a reason (up to 500 characters).",
					List.of(new FieldError("reason", "Enter a reason (up to 500 characters).")));
		}
		return reason.trim();
	}

	private static PlayerRequestException refused(String reason) {
		log.atWarn().setMessage("player.pairing_refused").addKeyValue("reason", reason).log();
		return new PlayerRequestException(HttpStatus.BAD_REQUEST, ApiErrorCodes.PLAYER_PAIRING_CODE_INVALID,
				"This pairing link is invalid or has expired. Start pairing again from your browser.");
	}

	static PlayerRequestException versionUnsupported() {
		return new PlayerRequestException(HttpStatus.UPGRADE_REQUIRED, ApiErrorCodes.PLAYER_VERSION_UNSUPPORTED,
				"Update the AOC Player.");
	}

}
