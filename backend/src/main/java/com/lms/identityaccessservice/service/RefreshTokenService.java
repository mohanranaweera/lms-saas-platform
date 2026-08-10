package com.lms.identityaccessservice.service;

import com.lms.identityaccessservice.domain.AccountStatus;
import com.lms.identityaccessservice.domain.DeviceSession;
import com.lms.identityaccessservice.domain.PlatformAdminSession;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.error.InvalidRefreshTokenException;
import com.lms.identityaccessservice.repository.DeviceSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminUserRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rotation/revocation logic shared by both session types (plan §9). Rotation
 * invalidates the old refresh token the instant the new one is issued - it
 * is an in-place {@code UPDATE} of the same row (never a new row), combined
 * with the DB's {@code UNIQUE(refresh_token_hash)} so a replayed stale hash
 * matches zero rows once replaced (see V5's/V6's header comments).
 *
 * <p>Logout ordering (plan §15/§21): the Postgres revoke is written and
 * flushed (authoritative) before the Redis cache entry is evicted, both
 * inside the same {@code @Transactional} service method.
 */
@Service
public class RefreshTokenService {

	private final DeviceSessionRepository deviceSessionRepository;

	private final PlatformAdminSessionRepository platformAdminSessionRepository;

	private final TenantUserRepository tenantUserRepository;

	private final PlatformAdminUserRepository platformAdminUserRepository;

	private final TokenService tokenService;

	private final DeviceSessionCacheService cacheService;

	public RefreshTokenService(DeviceSessionRepository deviceSessionRepository,
			PlatformAdminSessionRepository platformAdminSessionRepository, TenantUserRepository tenantUserRepository,
			PlatformAdminUserRepository platformAdminUserRepository, TokenService tokenService,
			DeviceSessionCacheService cacheService) {
		this.deviceSessionRepository = deviceSessionRepository;
		this.platformAdminSessionRepository = platformAdminSessionRepository;
		this.tenantUserRepository = tenantUserRepository;
		this.platformAdminUserRepository = platformAdminUserRepository;
		this.tokenService = tokenService;
		this.cacheService = cacheService;
	}

	@Transactional
	public RotationResult rotateTenantSession(String rawRefreshToken) {
		String hash = tokenService.hashRefreshToken(rawRefreshToken);
		DeviceSession session = deviceSessionRepository.findByRefreshTokenHash(hash)
			.orElseThrow(InvalidRefreshTokenException::new);
		Instant now = Instant.now();
		if (!session.isUsable(now)) {
			throw new InvalidRefreshTokenException();
		}
		TenantUser user = tenantUserRepository.findById(session.getUserId())
			.orElseThrow(InvalidRefreshTokenException::new);
		if (user.getStatus() == AccountStatus.SUSPENDED) {
			throw new InvalidRefreshTokenException();
		}
		String newRawToken = tokenService.generateOpaqueRefreshToken();
		session.rotate(tokenService.hashRefreshToken(newRawToken), now);
		deviceSessionRepository.save(session);
		String accessToken = tokenService.issueTenantAccessToken(user.getId(), session.getTenantId(), user.getRole(),
				session.getId());
		cacheService.markActive(session.getId(), session.getExpiresAt());
		return new RotationResult(accessToken, tokenService.accessTokenTtl(), newRawToken,
				Duration.between(now, session.getExpiresAt()));
	}

	@Transactional
	public void revokeTenantSession(UUID sessionId) {
		DeviceSession session = deviceSessionRepository.findById(sessionId)
			.orElseThrow(InvalidRefreshTokenException::new);
		session.revoke(Instant.now());
		deviceSessionRepository.save(session);
		cacheService.evict(sessionId);
	}

	@Transactional
	public RotationResult rotatePlatformAdminSession(String rawRefreshToken) {
		String hash = tokenService.hashRefreshToken(rawRefreshToken);
		PlatformAdminSession session = platformAdminSessionRepository.findByRefreshTokenHash(hash)
			.orElseThrow(InvalidRefreshTokenException::new);
		Instant now = Instant.now();
		if (!session.isUsable(now)) {
			throw new InvalidRefreshTokenException();
		}
		PlatformAdminUser admin = platformAdminUserRepository.findById(session.getAdminId())
			.orElseThrow(InvalidRefreshTokenException::new);
		if (admin.getStatus() == AccountStatus.SUSPENDED) {
			throw new InvalidRefreshTokenException();
		}
		String newRawToken = tokenService.generateOpaqueRefreshToken();
		session.rotate(tokenService.hashRefreshToken(newRawToken), now);
		platformAdminSessionRepository.save(session);
		String accessToken = tokenService.issuePlatformAdminAccessToken(admin.getId(), session.getId());
		cacheService.markActive(session.getId(), session.getExpiresAt());
		return new RotationResult(accessToken, tokenService.accessTokenTtl(), newRawToken,
				Duration.between(now, session.getExpiresAt()));
	}

	@Transactional
	public void revokePlatformAdminSession(UUID sessionId) {
		PlatformAdminSession session = platformAdminSessionRepository.findById(sessionId)
			.orElseThrow(InvalidRefreshTokenException::new);
		session.revoke(Instant.now());
		platformAdminSessionRepository.save(session);
		cacheService.evict(sessionId);
	}

}
