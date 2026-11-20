package com.lms.reportinganalytics.service;

import com.lms.reportinganalytics.config.StudentBehaviorReportProperties;
import com.lms.reportinganalytics.service.support.ReportRunRateLimiter;
import com.lms.reportinganalytics.service.support.ReportRunSnapshotStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class StudentBehaviorRunStore {
    private final ReportRunSnapshotStore store;
    private final ReportRunRateLimiter runLimiter;
    private final ReportRunRateLimiter pdfLimiter;
    private final ReportRunRateLimiter rowsLimiter;
    private final StudentBehaviorReportProperties properties;
    public StudentBehaviorRunStore(StringRedisTemplate redis, ObjectMapper mapper, StudentBehaviorReportProperties properties) {
        store = new ReportRunSnapshotStore(redis, mapper, "student-behavior", "choose a smaller course or month");
        runLimiter = new ReportRunRateLimiter(redis, "student-behavior");
        pdfLimiter = new ReportRunRateLimiter(redis, "student-behavior", "pdf");
        rowsLimiter = new ReportRunRateLimiter(redis, "student-behavior", "rows");
        this.properties = properties;
    }
    public void acquireRunSlot(UUID tenantId, UUID userId) { runLimiter.acquire(tenantId, userId, properties.getRunsPerWindow(), properties.getRunWindow()); }
    public void acquirePdfSlot(UUID tenantId, UUID userId) { pdfLimiter.acquire(tenantId, userId, properties.getPdfPerWindow(), properties.getRunWindow()); }
    public void acquireRowsSlot(UUID tenantId, UUID userId) { rowsLimiter.acquire(tenantId, userId, properties.getRowsPerWindow(), properties.getRunWindow()); }
    public void save(UUID tenantId, UUID userId, StudentBehaviorRun run) { store.save(tenantId, userId, run.runId(), run, properties.getMaxRunBytes()); }
    public Optional<StudentBehaviorRun> find(UUID tenantId, UUID userId, UUID runId) { return store.find(tenantId, userId, runId, StudentBehaviorRun.class, StudentBehaviorRun::runId); }
    public Duration ttl() { return ReportRunSnapshotStore.TTL; }
}
