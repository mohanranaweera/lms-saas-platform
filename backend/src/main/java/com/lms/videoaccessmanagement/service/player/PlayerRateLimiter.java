package com.lms.videoaccessmanagement.service.player;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window Redis counters for the AOC Player's unauthenticated endpoints
 * (VIDEO Phase 8, ADR-025 §4: per-IP and per-installation limits). Mirrors
 * identity's {@code AuthRateLimiter} (which is not part of that module's api
 * package): Redis is ephemeral state, and an outage fails OPEN - the codes are
 * 256-bit, single use and signature-bound, so the limit is defense in depth.
 */
@Component
public class PlayerRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(PlayerRateLimiter.class);

	private static final String PREFIX = "player:ratelimit:";

	private final StringRedisTemplate redis;

	public PlayerRateLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	/** Counts this attempt; {@code true} while at most {@code limit} attempts fall in the window. */
	public boolean tryAcquire(String key, int limit, Duration window) {
		try {
			Long count = redis.opsForValue().increment(PREFIX + key);
			if (count != null && count == 1L) {
				redis.expire(PREFIX + key, window);
			}
			return count == null || count <= limit;
		}
		catch (RuntimeException ex) {
			log.atWarn()
				.setMessage("player.rate_limit_unavailable")
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
			return true;
		}
	}

}
