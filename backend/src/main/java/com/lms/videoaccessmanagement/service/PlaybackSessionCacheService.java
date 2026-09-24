package com.lms.videoaccessmanagement.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis fast-path cache mapping a playback {@code jti} to its owning {@code
 * video_watch_session} id (Wave 5, plan §4) - mirrors {@code
 * identityaccessservice.service.DeviceSessionCacheService}'s exact shape and
 * reuses the SAME autoconfigured {@code StringRedisTemplate}/{@code
 * RedisConnectionFactory} bean (per {@code .claude/rules/security.md}'s "look
 * at DeviceSessionCacheService for the exact bean this project already has
 * configured - reuse that same bean, don't create a second Redis client").
 *
 * <p>Postgres ({@code video_watch_session}) remains the sole source of
 * truth - per {@code .claude/rules/architecture.md}, "Redis is a cache/
 * ephemeral-state layer, not a source of truth". Every real authorization
 * decision in {@code VideoPlaybackSessionService} is still made by loading
 * the actual {@code VideoWatchSession} row from Postgres and re-validating
 * its {@code status}/{@code playbackJti}/{@code studentId}/{@code tenantId}
 * on every call (PAR-20-02) - this cache is purely an optional fast-path
 * lookup/eviction-tracking aid, never itself trusted as authorization.
 */
@Service
public class PlaybackSessionCacheService {

	private static final String KEY_PREFIX = "lms:video:playback-session:";

	private final StringRedisTemplate redisTemplate;

	public PlaybackSessionCacheService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/** Caches {@code jti -> watchSessionId} with a TTL bounded by the session's own remaining lifetime. */
	public void cache(UUID jti, UUID watchSessionId, Instant expiresAt) {
		Duration ttl = Duration.between(Instant.now(), expiresAt);
		if (ttl.isZero() || ttl.isNegative()) {
			return;
		}
		redisTemplate.opsForValue().set(key(jti), watchSessionId.toString(), ttl);
	}

	public Optional<UUID> lookup(UUID jti) {
		String value = redisTemplate.opsForValue().get(key(jti));
		return value == null ? Optional.empty() : Optional.of(UUID.fromString(value));
	}

	/** Called whenever a session is revoked/ended - always after the authoritative Postgres write. */
	public void evict(UUID jti) {
		redisTemplate.delete(key(jti));
	}

	private String key(UUID jti) {
		return KEY_PREFIX + jti;
	}

}
