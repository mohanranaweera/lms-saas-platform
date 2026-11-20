package com.lms.reportinganalytics.service;

import com.lms.reportinganalytics.config.RegistrationReportProperties;
import com.lms.reportinganalytics.service.support.ReportRunRateLimiter;
import com.lms.reportinganalytics.service.support.ReportRunSnapshotStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Owner-bound Registration Report snapshots and operation limits. */
@Component
public class RegistrationRunStore {
	private final ReportRunSnapshotStore store;
	private final ReportRunRateLimiter runLimiter;
	private final ReportRunRateLimiter pdfLimiter;
	private final ReportRunRateLimiter rowsLimiter;
	private final RegistrationReportProperties properties;
	public RegistrationRunStore(StringRedisTemplate redis, ObjectMapper mapper, RegistrationReportProperties properties) {
		store = new ReportRunSnapshotStore(redis, mapper, "registrations", "choose a shorter date range or fewer courses");
		runLimiter = new ReportRunRateLimiter(redis, "registrations");
		pdfLimiter = new ReportRunRateLimiter(redis, "registrations", "pdf");
		rowsLimiter = new ReportRunRateLimiter(redis, "registrations", "rows");
		this.properties = properties;
	}
	public void acquireRunSlot(UUID tenantId, UUID userId) { runLimiter.acquire(tenantId, userId, properties.getRunsPerWindow(), properties.getRunWindow()); }
	public void acquirePdfSlot(UUID tenantId, UUID userId) { pdfLimiter.acquire(tenantId, userId, properties.getPdfPerWindow(), properties.getRunWindow()); }
	public void acquireRowsSlot(UUID tenantId, UUID userId) { rowsLimiter.acquire(tenantId, userId, properties.getRowsPerWindow(), properties.getRunWindow()); }
	public void save(UUID tenantId, UUID userId, RegistrationRun run) { store.save(tenantId, userId, run.runId(), run, properties.getMaxRunBytes()); }
	public Optional<RegistrationRun> find(UUID tenantId, UUID userId, UUID runId) { return store.find(tenantId, userId, runId, RegistrationRun.class, RegistrationRun::runId); }
	public Duration ttl() { return ReportRunSnapshotStore.TTL; }
}
