package com.lms.reportinganalytics.service;

import com.lms.reportinganalytics.config.UnpaidStudentsReportProperties;
import com.lms.reportinganalytics.service.support.ReportRunRateLimiter;
import com.lms.reportinganalytics.service.support.ReportRunSnapshotStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Owner-bound Redis snapshots and operation limits for RPT Unpaid Students. */
@Component
public class UnpaidStudentsRunStore {

	private final ReportRunSnapshotStore store;
	private final ReportRunRateLimiter runLimiter;
	private final ReportRunRateLimiter pdfLimiter;
	private final ReportRunRateLimiter rowsLimiter;
	private final UnpaidStudentsReportProperties properties;

	public UnpaidStudentsRunStore(StringRedisTemplate redis, ObjectMapper mapper,
			UnpaidStudentsReportProperties properties) {
		this.store = new ReportRunSnapshotStore(redis, mapper, "unpaid-students",
				"choose a course or month with fewer records");
		this.runLimiter = new ReportRunRateLimiter(redis, "unpaid-students");
		this.pdfLimiter = new ReportRunRateLimiter(redis, "unpaid-students", "pdf");
		this.rowsLimiter = new ReportRunRateLimiter(redis, "unpaid-students", "rows");
		this.properties = properties;
	}

	public void acquireRunSlot(UUID tenantId, UUID userId) {
		runLimiter.acquire(tenantId, userId, properties.getRunsPerWindow(), properties.getRunWindow());
	}
	public void acquirePdfSlot(UUID tenantId, UUID userId) {
		pdfLimiter.acquire(tenantId, userId, properties.getPdfPerWindow(), properties.getRunWindow());
	}
	public void acquireRowsSlot(UUID tenantId, UUID userId) {
		rowsLimiter.acquire(tenantId, userId, properties.getRowsPerWindow(), properties.getRunWindow());
	}
	public void save(UUID tenantId, UUID userId, UnpaidStudentsRun run) {
		store.save(tenantId, userId, run.runId(), run, properties.getMaxRunBytes());
	}
	public Optional<UnpaidStudentsRun> find(UUID tenantId, UUID userId, UUID runId) {
		return store.find(tenantId, userId, runId, UnpaidStudentsRun.class, UnpaidStudentsRun::runId);
	}
	public Duration ttl() { return ReportRunSnapshotStore.TTL; }

}
