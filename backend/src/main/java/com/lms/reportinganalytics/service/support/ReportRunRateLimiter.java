package com.lms.reportinganalytics.service.support;

import com.lms.common.error.TooManyRequestsException;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Shared by RPT-01 and RPT-02: fixed-window Redis counter per (report, tenant,
 * user) on {@code POST .../runs} - the same mechanism as identity's {@code
 * AuthRateLimiter} and DISC-01's {@code DiscountCodeAttemptLimiter}. Beyond
 * the limit the request is the standard {@code 429 TOO_MANY_REQUESTS} and a
 * {@code SECURITY} log entry is written. Tenant and user come from the trusted
 * context. If Redis is unreachable the limiter fails open (the run itself then
 * fails with 503 when the snapshot cannot be stored).
 */
public final class ReportRunRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(ReportRunRateLimiter.class);

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	private final StringRedisTemplate redis;

	private final String prefix;

	/** @param namespace the report's key namespace, e.g. {@code teacher-income}. */
	public ReportRunRateLimiter(StringRedisTemplate redis, String namespace) {
		this(redis, namespace, "runs");
	}

	/**
	 * @param action the limited operation, e.g. {@code runs}, {@code pdf},
	 * {@code rows} (key {@code report:<namespace>:<action>:<tenant>:<user>}).
	 */
	public ReportRunRateLimiter(StringRedisTemplate redis, String namespace, String action) {
		this.redis = redis;
		this.prefix = "report:" + namespace + ":" + action + ":";
	}

	public void acquire(UUID tenantId, UUID userId, int runsPerWindow, Duration window) {
		String key = prefix + tenantId + ":" + userId;
		Long count;
		try {
			count = redis.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redis.expire(key, window);
			}
		}
		catch (RuntimeException ex) {
			log.atWarn()
				.setMessage("report.run_rate_limit_unavailable")
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
			return;
		}
		if (count != null && count > runsPerWindow) {
			securityLog.atWarn()
				.setMessage("report.run_rate_limited")
				.addKeyValue("tenantId", tenantId)
				.addKeyValue("userId", userId)
				.addKeyValue("runs", count)
				.addKeyValue("limit", runsPerWindow)
				.addKeyValue("window", window)
				.log();
			throw new TooManyRequestsException("Too many reports requested - please wait a minute and try again.");
		}
	}

}
