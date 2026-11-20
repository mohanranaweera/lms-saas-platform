package com.lms.reportinganalytics.service;

import com.lms.reportinganalytics.config.AttendanceReportProperties;
import com.lms.reportinganalytics.service.support.ReportRunRateLimiter;
import com.lms.reportinganalytics.service.support.ReportRunSnapshotStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis-backed, owner-bound RPT-03 run snapshots and operation limits. */
@Component
public class AttendanceReportRunStore {

	private static final String NAMESPACE = "attendance";

	private final ReportRunSnapshotStore store;

	private final ReportRunRateLimiter runLimiter;

	private final ReportRunRateLimiter pdfLimiter;

	private final AttendanceReportProperties properties;

	public AttendanceReportRunStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
			AttendanceReportProperties properties) {
		this.store = new ReportRunSnapshotStore(redisTemplate, objectMapper, NAMESPACE,
				"choose a shorter date range or a course with fewer attendance records");
		this.runLimiter = new ReportRunRateLimiter(redisTemplate, NAMESPACE);
		this.pdfLimiter = new ReportRunRateLimiter(redisTemplate, NAMESPACE, "pdf");
		this.properties = properties;
	}

	public void acquireRunSlot(UUID tenantId, UUID userId) {
		runLimiter.acquire(tenantId, userId, properties.getRunsPerWindow(), properties.getRunWindow());
	}

	public void acquirePdfSlot(UUID tenantId, UUID userId) {
		pdfLimiter.acquire(tenantId, userId, properties.getPdfPerWindow(), properties.getRunWindow());
	}

	public void save(UUID tenantId, UUID userId, AttendanceReportRun run) {
		store.save(tenantId, userId, run.runId(), run, properties.getMaxRunBytes());
	}

	public Optional<AttendanceReportRun> find(UUID tenantId, UUID userId, UUID runId) {
		return store.find(tenantId, userId, runId, AttendanceReportRun.class, AttendanceReportRun::runId);
	}

	public Duration ttl() {
		return ReportRunSnapshotStore.TTL;
	}

}
