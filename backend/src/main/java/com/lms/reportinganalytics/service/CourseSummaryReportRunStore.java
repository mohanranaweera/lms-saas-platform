package com.lms.reportinganalytics.service;

import com.lms.reportinganalytics.config.CourseSummaryReportProperties;
import com.lms.reportinganalytics.service.support.ReportRunRateLimiter;
import com.lms.reportinganalytics.service.support.ReportRunSnapshotStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Redis-backed, owner-bound RPT-05 snapshot and operation limits. */
@Component
public class CourseSummaryReportRunStore {

	private static final String NAMESPACE = "course-summary";

	private final ReportRunSnapshotStore store;
	private final ReportRunRateLimiter runLimiter;
	private final ReportRunRateLimiter pdfLimiter;
	private final CourseSummaryReportProperties properties;

	public CourseSummaryReportRunStore(StringRedisTemplate redis, ObjectMapper objectMapper,
			CourseSummaryReportProperties properties) {
		this.store = new ReportRunSnapshotStore(redis, objectMapper, NAMESPACE,
				"choose a shorter period or course with fewer report facts");
		this.runLimiter = new ReportRunRateLimiter(redis, NAMESPACE);
		this.pdfLimiter = new ReportRunRateLimiter(redis, NAMESPACE, "pdf");
		this.properties = properties;
	}

	public void acquireRunSlot(UUID tenantId, UUID userId) {
		runLimiter.acquire(tenantId, userId, properties.getRunsPerWindow(), properties.getRunWindow());
	}

	public void acquirePdfSlot(UUID tenantId, UUID userId) {
		pdfLimiter.acquire(tenantId, userId, properties.getPdfPerWindow(), properties.getRunWindow());
	}

	public void save(UUID tenantId, UUID userId, CourseSummaryReportRun run) {
		store.save(tenantId, userId, run.runId(), run, properties.getMaxRunBytes());
	}

	public Optional<CourseSummaryReportRun> find(UUID tenantId, UUID userId, UUID runId) {
		return store.find(tenantId, userId, runId, CourseSummaryReportRun.class, CourseSummaryReportRun::runId);
	}

	public Duration ttl() { return ReportRunSnapshotStore.TTL; }

}
