package com.lms.paymentmanagement.discount.service;

import com.lms.common.error.ApplicationException;
import com.lms.paymentmanagement.discount.config.DiscountProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * DISC-01 code-probing guard (owner decision 2026-10-21, "harden fully"): a
 * fixed-window Redis counter per (tenant, student) of discount-code attempts
 * that resolve to {@code INVALID} on {@code POST /api/v1/checkout/quote} and
 * {@code /orders}. Same mechanism as identity's {@code AuthRateLimiter} and
 * video's {@code PlayerRateLimiter} (neither is in an {@code api} package, so
 * this module keeps its own copy).
 *
 * <ul>
 * <li>{@link #requireNotLimited} - before a code is evaluated: once the student
 * has reached the limit, ANY request carrying a code is {@code 429
 * DISCOUNT_CODE_RATE_LIMITED} until the window ends (so a locked-out caller
 * cannot keep probing, even with valid codes). Requests without a code are
 * never affected.</li>
 * <li>{@link #recordInvalid} - after a code resolved to {@code INVALID}:
 * counts it; an attempt beyond the limit (a concurrent burst) is 429 too.</li>
 * </ul>
 * Every refusal writes a {@code SECURITY} log entry. Tenant and student come
 * from the trusted context of the caller, never from the request. Redis is
 * ephemeral state: when it is unreachable the limiter fails OPEN (a Redis
 * outage must not block checkout) and logs a warning - the code itself is
 * still validated server-side.
 */
@Component
public class DiscountCodeAttemptLimiter {

	public static final String DISCOUNT_CODE_RATE_LIMITED = "DISCOUNT_CODE_RATE_LIMITED";

	private static final Logger log = LoggerFactory.getLogger(DiscountCodeAttemptLimiter.class);

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	private static final String PREFIX = "discount:code-invalid:";

	private final StringRedisTemplate redis;

	private final DiscountProperties properties;

	public DiscountCodeAttemptLimiter(StringRedisTemplate redis, DiscountProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	/** 429 - too many invalid discount-code attempts in the window. */
	public static class DiscountCodeRateLimitedException extends ApplicationException {

		public DiscountCodeRateLimitedException() {
			super(HttpStatus.TOO_MANY_REQUESTS, DISCOUNT_CODE_RATE_LIMITED,
					"Too many invalid discount codes - please wait a few minutes before trying another code");
		}

	}

	public void requireNotLimited(UUID tenantId, UUID studentId) {
		int limit = properties.getCodeAttempts().getMaxInvalid();
		Long count = read(key(tenantId, studentId));
		if (count != null && count >= limit) {
			refuse(tenantId, studentId, count, "locked");
		}
	}

	public void recordInvalid(UUID tenantId, UUID studentId) {
		String key = key(tenantId, studentId);
		try {
			Long count = redis.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redis.expire(key, properties.getCodeAttempts().getWindow());
			}
			if (count != null && count > properties.getCodeAttempts().getMaxInvalid()) {
				refuse(tenantId, studentId, count, "exceeded");
			}
		}
		catch (DiscountCodeRateLimitedException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			unavailable(ex);
		}
	}

	private Long read(String key) {
		try {
			String value = redis.opsForValue().get(key);
			return value == null ? null : Long.parseLong(value);
		}
		catch (RuntimeException ex) {
			unavailable(ex);
			return null;
		}
	}

	private void refuse(UUID tenantId, UUID studentId, long count, String reason) {
		securityLog.atWarn()
			.setMessage("discount.code_rate_limited")
			.addKeyValue("tenantId", tenantId)
			.addKeyValue("studentId", studentId)
			.addKeyValue("invalidAttempts", count)
			.addKeyValue("limit", properties.getCodeAttempts().getMaxInvalid())
			.addKeyValue("window", properties.getCodeAttempts().getWindow())
			.addKeyValue("reason", reason)
			.log();
		throw new DiscountCodeRateLimitedException();
	}

	private static void unavailable(RuntimeException ex) {
		log.atWarn()
			.setMessage("discount.code_rate_limit_unavailable")
			.addKeyValue("errorType", ex.getClass().getSimpleName())
			.log();
	}

	private static String key(UUID tenantId, UUID studentId) {
		return PREFIX + tenantId + ":" + studentId;
	}

}
