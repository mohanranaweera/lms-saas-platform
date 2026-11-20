package com.lms.identityaccessservice.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counters in Redis for AUTH-PWD-01's rate limits (forgot
 * password, link verify/complete, change-password lockout, invite resend).
 * Redis is ephemeral state here, never a source of truth
 * ({@code .claude/rules/architecture.md}).
 *
 * <p>If Redis is unreachable the limiter fails OPEN (allows) and logs, so an
 * outage never locks every user out of password recovery; the other controls
 * (token entropy, single use, expiry) still apply.
 */
@Component
public class AuthRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(AuthRateLimiter.class);

	private static final String PREFIX = "auth:ratelimit:";

	private final StringRedisTemplate redis;

	public AuthRateLimiter(StringRedisTemplate redis) {
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
			log.atWarn().setMessage("auth.rate_limit_unavailable").addKeyValue("errorType", ex.getClass().getSimpleName()).log();
			return true;
		}
	}

	/** Whether the window's count has already reached {@code limit}, without counting an attempt. */
	public boolean isAtLimit(String key, int limit) {
		try {
			String value = redis.opsForValue().get(PREFIX + key);
			return value != null && Long.parseLong(value) >= limit;
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	public void reset(String key) {
		try {
			redis.delete(PREFIX + key);
		}
		catch (RuntimeException ex) {
			// best effort
		}
	}

}
