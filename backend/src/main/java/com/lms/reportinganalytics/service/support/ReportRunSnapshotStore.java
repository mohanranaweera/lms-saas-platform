package com.lms.reportinganalytics.service.support;

import com.lms.common.error.ServiceUnavailableException;
import com.lms.reportinganalytics.service.ReportValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared by RPT-01 and RPT-02: a short-lived report run snapshot in the
 * platform Redis (ephemeral state, never the source of truth - a lost run is
 * regenerated from the ledger). The key embeds the report namespace, the
 * tenant AND the user the run was computed for, so a lookup by another user or
 * tenant addresses a different key and finds nothing; the owner binding is
 * re-checked on read (defence in depth). Expiry is Redis TTL ({@link #TTL}).
 * Redis failures are {@code 503}; an oversized snapshot is a {@code 422
 * REPORT_TOO_LARGE} and is never stored. Works across horizontally scaled
 * instances (no in-JVM state).
 */
public final class ReportRunSnapshotStore {

	public static final Duration TTL = Duration.ofMinutes(15);

	/** What is stored: the owner binding plus the report. */
	public record StoredRun<T>(UUID tenantId, UUID userId, T report) {

	}

	private final StringRedisTemplate redisTemplate;

	private final ObjectMapper objectMapper;

	private final String keyPrefix;

	private final String tooLargeHint;

	/**
	 * @param namespace the report's key namespace, e.g. {@code teacher-income}.
	 * @param tooLargeHint how to narrow the filter, shown in the 422 field error.
	 */
	public ReportRunSnapshotStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper, String namespace,
			String tooLargeHint) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
		this.keyPrefix = "lms:report:" + namespace + ":";
		this.tooLargeHint = tooLargeHint;
	}

	/** @throws ReportValidationException 422 if the serialized run exceeds {@code maxBytes}. */
	public <T> void save(UUID tenantId, UUID userId, UUID runId, T report, int maxBytes) {
		String json;
		try {
			json = objectMapper.writeValueAsString(new StoredRun<>(tenantId, userId, report));
		}
		catch (JacksonException ex) {
			throw new ServiceUnavailableException("Reports are not available right now.");
		}
		int bytes = json.getBytes(StandardCharsets.UTF_8).length;
		if (bytes > maxBytes) {
			throw ReportValidationException.snapshotTooLarge(bytes, maxBytes, tooLargeHint);
		}
		try {
			redisTemplate.opsForValue().set(key(tenantId, userId, runId), json, TTL);
		}
		catch (DataAccessException ex) {
			throw new ServiceUnavailableException("Reports are not available right now.");
		}
	}

	public <T> Optional<T> find(UUID tenantId, UUID userId, UUID runId, Class<T> type, Function<T, UUID> runIdOf) {
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
			JavaType storedType = objectMapper.getTypeFactory().constructParametricType(StoredRun.class, type);
			StoredRun<T> run = objectMapper.readValue(json, storedType);
			if (!tenantId.equals(run.tenantId()) || !userId.equals(run.userId()) || run.report() == null
					|| !runId.equals(runIdOf.apply(run.report()))) {
				return Optional.empty();
			}
			return Optional.of(run.report());
		}
		catch (JacksonException ex) {
			return Optional.empty();
		}
	}

	private String key(UUID tenantId, UUID userId, UUID runId) {
		return keyPrefix + tenantId + ":" + userId + ":" + runId;
	}

}
