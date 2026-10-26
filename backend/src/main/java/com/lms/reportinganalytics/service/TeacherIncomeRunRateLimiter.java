package com.lms.reportinganalytics.service;

import com.lms.reportinganalytics.config.TeacherIncomeReportProperties;
import com.lms.reportinganalytics.service.support.ReportRunRateLimiter;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * RPT-01: the shared {@link ReportRunRateLimiter} (fixed-window Redis counter
 * per (tenant, user), key {@code report:teacher-income:runs:<tenant>:<user>})
 * on {@code POST /api/v1/reports/teacher-income/runs}, bounded by {@code
 * runsPerWindow} / {@code runWindow}. Beyond the limit: {@code 429} plus a
 * {@code SECURITY} log entry; fails open if Redis is unreachable.
 */
@Component
public class TeacherIncomeRunRateLimiter {

	private final ReportRunRateLimiter limiter;

	private final TeacherIncomeReportProperties properties;

	public TeacherIncomeRunRateLimiter(StringRedisTemplate redis, TeacherIncomeReportProperties properties) {
		this.limiter = new ReportRunRateLimiter(redis, "teacher-income");
		this.properties = properties;
	}

	public void acquire(UUID tenantId, UUID userId) {
		limiter.acquire(tenantId, userId, properties.getRunsPerWindow(), properties.getRunWindow());
	}

}
