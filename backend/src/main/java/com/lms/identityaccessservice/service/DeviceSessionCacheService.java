package com.lms.identityaccessservice.service;

import com.lms.identityaccessservice.config.SessionProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis fast-path cache for session validity (plan §9/§15) - Postgres
 * remains the sole authority. A cache hit is trusted without a Postgres read
 * (that is the point of the fast path), but every entry is bounded by a
 * short TTL ({@link SessionProperties#getCacheTtl()}) so a revoked/logged
 * -out session can never be reported "valid" for longer than that window -
 * defense in depth against the logout-invalidation race, not a substitute
 * for the Postgres write on logout/rotation itself. A cache miss always
 * falls back to a Postgres read (see {@code JwtAuthenticationFilter}).
 *
 * <p>Deliberately generic across both {@code device_session} and {@code
 * platform_admin_session} - both are UUIDv7 primary keys from disjoint
 * tables, so a single key namespace keyed only by session id cannot collide
 * between the two. Role/status are never cached here (plan §9) - only a
 * boolean "this session id is currently active" fact.
 */
@Service
public class DeviceSessionCacheService {

	private static final String KEY_PREFIX = "lms:session:active:";

	private final StringRedisTemplate redisTemplate;

	private final SessionProperties sessionProperties;

	public DeviceSessionCacheService(StringRedisTemplate redisTemplate, SessionProperties sessionProperties) {
		this.redisTemplate = redisTemplate;
		this.sessionProperties = sessionProperties;
	}

	public boolean isActive(UUID sessionId) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(key(sessionId)));
	}

	/** Caches "active" for the lesser of the session's remaining lifetime and the configured bound. */
	public void markActive(UUID sessionId, Instant sessionExpiresAt) {
		Duration ttl = boundedTtl(sessionExpiresAt);
		if (ttl.isZero() || ttl.isNegative()) {
			return;
		}
		redisTemplate.opsForValue().set(key(sessionId), "1", ttl);
	}

	/** Called on logout/revocation, always after the authoritative Postgres write (plan §15/§21). */
	public void evict(UUID sessionId) {
		redisTemplate.delete(key(sessionId));
	}

	private Duration boundedTtl(Instant sessionExpiresAt) {
		Duration untilExpiry = Duration.between(Instant.now(), sessionExpiresAt);
		Duration bound = sessionProperties.getCacheTtl();
		return untilExpiry.compareTo(bound) < 0 ? untilExpiry : bound;
	}

	private String key(UUID sessionId) {
		return KEY_PREFIX + sessionId;
	}

}
