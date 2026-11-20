package com.lms.reportinganalytics.service;

import com.lms.reportinganalytics.config.StudentPaymentReportProperties;
import com.lms.reportinganalytics.service.support.ReportRunRateLimiter;
import com.lms.reportinganalytics.service.support.ReportRunSnapshotStore;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * RPT-02 run snapshot (header + every row) in the shared {@link
 * ReportRunSnapshotStore} under the {@code student-payments} namespace, bound
 * to (tenant, user), TTL 15 minutes, size-capped by {@code maxRunBytes}; plus
 * the per-(tenant, user) {@code POST /runs} rate limit ({@link
 * ReportRunRateLimiter}), plus separate limits on page and PDF reads, which
 * deserialize the whole snapshot. Previews, grouped pages and PDF exports read
 * only the stored run - never recompute.
 *
 * <p>Deliberately no in-process cache of the deserialized / grouped run: an
 * in-JVM copy could outlive the Redis TTL or an evicted / deleted key (a run
 * must disappear with its key), would differ between horizontally scaled
 * instances and would hold up to the snapshot bound in heap per entry. The
 * per-request cost is bounded instead by {@code maxRunBytes} (8 MB), the
 * 20,000-row cap and these rate limits; grouping is an in-memory sort of at
 * most 20,000 rows.
 */
@Component
public class StudentPaymentRunStore {

	private static final String NAMESPACE = "student-payments";

	private final ReportRunSnapshotStore store;

	private final ReportRunRateLimiter limiter;

	private final ReportRunRateLimiter pdfLimiter;

	private final ReportRunRateLimiter rowsLimiter;

	private final StudentPaymentReportProperties properties;

	public StudentPaymentRunStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
			StudentPaymentReportProperties properties) {
		this.store = new ReportRunSnapshotStore(redisTemplate, objectMapper, NAMESPACE,
				"choose a shorter period or fewer courses");
		this.limiter = new ReportRunRateLimiter(redisTemplate, NAMESPACE);
		this.pdfLimiter = new ReportRunRateLimiter(redisTemplate, NAMESPACE, "pdf");
		this.rowsLimiter = new ReportRunRateLimiter(redisTemplate, NAMESPACE, "rows");
		this.properties = properties;
	}

	public void acquireRunSlot(UUID tenantId, UUID userId) {
		limiter.acquire(tenantId, userId, properties.getRunsPerWindow(), properties.getRunWindow());
	}

	/** {@code GET .../pdf} per (tenant, user): {@code pdfPerWindow} per {@code runWindow}, else 429. */
	public void acquirePdfSlot(UUID tenantId, UUID userId) {
		pdfLimiter.acquire(tenantId, userId, properties.getPdfPerWindow(), properties.getRunWindow());
	}

	/** {@code GET .../rows} per (tenant, user): {@code rowsPerWindow} per {@code runWindow}, else 429. */
	public void acquireRowsSlot(UUID tenantId, UUID userId) {
		rowsLimiter.acquire(tenantId, userId, properties.getRowsPerWindow(), properties.getRunWindow());
	}

	/** @throws ReportValidationException 422 if the serialized run exceeds {@code maxRunBytes}. */
	public void save(UUID tenantId, UUID userId, StudentPaymentRun run) {
		store.save(tenantId, userId, run.runId(), run, properties.getMaxRunBytes());
	}

	public Optional<StudentPaymentRun> find(UUID tenantId, UUID userId, UUID runId) {
		return store.find(tenantId, userId, runId, StudentPaymentRun.class, StudentPaymentRun::runId);
	}

}
