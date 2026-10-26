package com.lms.reportinganalytics.service;

import com.lms.reportinganalytics.config.UndoPaymentReportProperties;
import com.lms.reportinganalytics.service.support.ReportRunRateLimiter;
import com.lms.reportinganalytics.service.support.ReportRunSnapshotStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class UndoPaymentRunStore {
	private final ReportRunSnapshotStore store;
	private final ReportRunRateLimiter runLimiter;
	private final ReportRunRateLimiter pdfLimiter;
	private final ReportRunRateLimiter rowsLimiter;
	private final UndoPaymentReportProperties properties;
	public UndoPaymentRunStore(StringRedisTemplate redis, ObjectMapper mapper, UndoPaymentReportProperties properties) {
		store = new ReportRunSnapshotStore(redis, mapper, "undo-payments", "choose a shorter date range or fewer courses");
		runLimiter = new ReportRunRateLimiter(redis, "undo-payments");
		pdfLimiter = new ReportRunRateLimiter(redis, "undo-payments", "pdf");
		rowsLimiter = new ReportRunRateLimiter(redis, "undo-payments", "rows");
		this.properties = properties;
	}
	public void acquireRunSlot(UUID tenantId, UUID userId) { runLimiter.acquire(tenantId, userId, properties.getRunsPerWindow(), properties.getRunWindow()); }
	public void acquirePdfSlot(UUID tenantId, UUID userId) { pdfLimiter.acquire(tenantId, userId, properties.getPdfPerWindow(), properties.getRunWindow()); }
	public void acquireRowsSlot(UUID tenantId, UUID userId) { rowsLimiter.acquire(tenantId, userId, properties.getRowsPerWindow(), properties.getRunWindow()); }
	public void save(UUID tenantId, UUID userId, UndoPaymentRun run) { store.save(tenantId, userId, run.runId(), run, properties.getMaxRunBytes()); }
	public Optional<UndoPaymentRun> find(UUID tenantId, UUID userId, UUID runId) { return store.find(tenantId, userId, runId, UndoPaymentRun.class, UndoPaymentRun::runId); }
	public ReportRunSnapshotStore snapshotStore() { return store; }
}
