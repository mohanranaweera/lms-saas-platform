package com.lms.reportinganalytics.service;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.reportinganalytics.config.TeacherIncomeReportProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * RPT-01 short-lived report run snapshot, held in the platform Redis (the
 * same ephemeral-state layer the app already uses for OAuth state, playback
 * sessions and rate limits; Redis is never the source of truth - a lost run is
 * simply regenerated from the ledger). The key embeds the tenant AND the user
 * the run was computed for, so a lookup by another user or another tenant
 * addresses a different key and finds nothing (404 to the caller); expiry is
 * enforced by Redis TTL ({@link #TTL}). Exports read only this stored dataset
 * and never recompute with other filters. Works across horizontally scaled
 * instances (no in-JVM state).
 */
@Component
public class TeacherIncomeRunStore {

	public static final Duration TTL = Duration.ofMinutes(15);

	private static final String KEY_PREFIX = "lms:report:teacher-income:";

	/** What is stored: the owner binding is re-checked on read (defence in depth). */
	record StoredRun(UUID tenantId, UUID userId, TeacherIncomeReport report) {

	}

	private final StringRedisTemplate redisTemplate;

	private final ObjectMapper objectMapper;

	private final TeacherIncomeReportProperties properties;

	public TeacherIncomeRunStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
			TeacherIncomeReportProperties properties) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	/** @throws ReportValidationException 422 if the serialized run exceeds {@code maxRunBytes}. */
	public void save(UUID tenantId, UUID userId, TeacherIncomeReport report) {
		String json;
		try {
			json = objectMapper.writeValueAsString(new StoredRun(tenantId, userId, report));
		}
		catch (JacksonException ex) {
			throw new ServiceUnavailableException("Reports are not available right now.");
		}
		int bytes = json.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > properties.getMaxRunBytes()) {
			throw ReportValidationException.snapshotTooLarge(bytes, properties.getMaxRunBytes());
		}
		try {
			redisTemplate.opsForValue().set(key(tenantId, userId, report.runId()), json, TTL);
		}
		catch (DataAccessException ex) {
			throw new ServiceUnavailableException("Reports are not available right now.");
		}
	}

	public Optional<TeacherIncomeReport> find(UUID tenantId, UUID userId, UUID runId) {
		String json;
		try {
			json = redisTemplate.opsForValue().get(key(tenantId, userId, runId));
		}
		catch (DataAccessException ex) {
			throw new ServiceUnavailableException("Reports are not available right now.");
		}
		if (json == null) {
			return Optional.empty();
		}
		try {
			StoredRun run = objectMapper.readValue(json, StoredRun.class);
			if (!tenantId.equals(run.tenantId()) || !userId.equals(run.userId()) || run.report() == null
					|| !runId.equals(run.report().runId())) {
				return Optional.empty();
			}
			return Optional.of(run.report());
		}
		catch (JacksonException ex) {
			return Optional.empty();
		}
	}

	private static String key(UUID tenantId, UUID userId, UUID runId) {
		return KEY_PREFIX + tenantId + ":" + userId + ":" + runId;
	}

}
