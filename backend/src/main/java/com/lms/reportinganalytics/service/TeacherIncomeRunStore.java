package com.lms.reportinganalytics.service;

import com.lms.reportinganalytics.config.TeacherIncomeReportProperties;
import com.lms.reportinganalytics.service.support.ReportRunSnapshotStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * RPT-01 short-lived report run snapshot - the shared {@link
 * ReportRunSnapshotStore} under the {@code teacher-income} namespace (key
 * {@code lms:report:teacher-income:<tenant>:<user>:<run>}), bounded by {@code
 * maxRunBytes}. A lookup by another user or another tenant finds nothing (404
 * to the caller); expiry is Redis TTL ({@link #TTL}). Exports read only this
 * stored dataset and never recompute with other filters.
 */
@Component
public class TeacherIncomeRunStore {

	public static final Duration TTL = ReportRunSnapshotStore.TTL;

	private final ReportRunSnapshotStore store;

	private final TeacherIncomeReportProperties properties;

	public TeacherIncomeRunStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
			TeacherIncomeReportProperties properties) {
		this.store = new ReportRunSnapshotStore(redisTemplate, objectMapper, "teacher-income",
				"choose a shorter period or fewer teachers");
		this.properties = properties;
	}

	/** @throws ReportValidationException 422 if the serialized run exceeds {@code maxRunBytes}. */
	public void save(UUID tenantId, UUID userId, TeacherIncomeReport report) {
		store.save(tenantId, userId, report.runId(), report, properties.getMaxRunBytes());
	}

	public Optional<TeacherIncomeReport> find(UUID tenantId, UUID userId, UUID runId) {
		return store.find(tenantId, userId, runId, TeacherIncomeReport.class, TeacherIncomeReport::runId);
	}

}
