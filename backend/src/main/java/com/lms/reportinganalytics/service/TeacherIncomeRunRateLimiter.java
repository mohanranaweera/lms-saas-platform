package com.lms.reportinganalytics.service;

import com.lms.common.error.TooManyRequestsException;
import com.lms.reportinganalytics.config.TeacherIncomeReportProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * RPT-01: fixed-window Redis counter per (tenant, user) on {@code POST
 * /api/v1/reports/teacher-income/runs} - the same mechanism as identity's
 * {@code AuthRateLimiter}, video's {@code PlayerRateLimiter} and DISC-01's
 * {@code DiscountCodeAttemptLimiter} (none is in an {@code api} package, so
 * this module keeps its own copy, as they do). Beyond the limit the request is
 * the standard {@code 429 TOO_MANY_REQUESTS} and a {@code SECURITY} log entry
 * is written. Tenant and user come from the trusted context. If Redis is
 * unreachable the limiter fails open (the run itself then fails with 503 when
 * the snapshot cannot be stored).
 */
@Component
public class TeacherIncomeRunRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(TeacherIncomeRunRateLimiter.class);

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	private static final String PREFIX = "report:teacher-income:runs:";

	private final StringRedisTemplate redis;

	private final TeacherIncomeReportProperties properties;

	public TeacherIncomeRunRateLimiter(StringRedisTemplate redis, TeacherIncomeReportProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	public void acquire(UUID tenantId, UUID userId) {
		String key = PREFIX + tenantId + ":" + userId;
		Long count;
		try {
			count = redis.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redis.expire(key, properties.getRunWindow());
			}
		}
		catch (RuntimeException ex) {
			log.atWarn()
				.setMessage("report.run_rate_limit_unavailable")
				.addKeyValue("errorType", ex.getClass().getSimpleName())
				.log();
			return;
		}
		if (count != null && count > properties.getRunsPerWindow()) {
			securityLog.atWarn()
				.setMessage("report.run_rate_limited")
				.addKeyValue("tenantId", tenantId)
				.addKeyValue("userId", userId)
				.addKeyValue("runs", count)
				.addKeyValue("limit", properties.getRunsPerWindow())
				.addKeyValue("window", properties.getRunWindow())
				.log();
			throw new TooManyRequestsException("Too many reports requested - please wait a minute and try again.");
		}
	}

}
