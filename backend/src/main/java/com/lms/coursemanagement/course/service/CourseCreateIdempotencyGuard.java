package com.lms.coursemanagement.course.service;

import com.lms.common.error.ConflictException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * KLASS-T4a double-submit guard for {@code POST /api/v1/courses} with an
 * {@code Idempotency-Key} header. No schema exists for request idempotency
 * and this task may not add a migration, so the guard lives in Redis (an
 * ephemeral layer, per {@code .claude/rules/architecture.md}) - it is a UX
 * protection against a double-clicked "Create Course", not a source of truth.
 *
 * <p>Key: {@code course:create:idem:<tenantId>:<actorId>:<sha256(header)>} -
 * scoped to the trusted tenant AND the authenticated actor, so one user's key
 * can never replay another user's (or tenant's) course. Lifecycle:
 * <ol>
 * <li>{@link #begin}: a stored course id = replay; {@code PENDING} = the same
 * request is still running (409); otherwise {@code SET NX PENDING} (2 min).</li>
 * <li>{@link Claim#complete}: once the creating transaction COMMITS the key
 * maps to the course id for 24 h; on rollback the key is deleted.</li>
 * </ol>
 * If Redis is unreachable the guard fails open (logs and creates normally) -
 * course creation must not depend on a cache.
 */
@Component
class CourseCreateIdempotencyGuard {

	private static final Logger log = LoggerFactory.getLogger(CourseCreateIdempotencyGuard.class);

	static final String KEY_PREFIX = "course:create:idem:";

	static final String PENDING = "PENDING";

	static final Duration PENDING_TTL = Duration.ofMinutes(2);

	static final Duration RESULT_TTL = Duration.ofHours(24);

	static final int MAX_KEY_LENGTH = 200;

	private final StringRedisTemplate redisTemplate;

	CourseCreateIdempotencyGuard(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/** Outcome of {@link #begin}: either a replay of an earlier course id, or a claim to complete. */
	record Begin(UUID replayCourseId, Claim claim) {

		static final Begin NONE = new Begin(null, null);

		boolean isReplay() {
			return replayCourseId != null;
		}

	}

	/**
	 * A claimed key, bound to the current transaction: when it commits after
	 * {@link #complete} the key maps to the course id; on rollback, or if
	 * {@link #complete} was never reached, the claim is released at once.
	 */
	final class Claim {

		private final String redisKey;

		private volatile UUID courseId;

		private Claim(String redisKey) {
			this.redisKey = redisKey;
			if (TransactionSynchronizationManager.isSynchronizationActive()) {
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCompletion(int status) {
						UUID created = courseId;
						if (status == STATUS_COMMITTED && created != null) {
							store(redisKey, created.toString(), RESULT_TTL);
						}
						else {
							release(redisKey);
						}
					}
				});
			}
		}

		void complete(UUID createdCourseId) {
			this.courseId = createdCourseId;
			if (!TransactionSynchronizationManager.isSynchronizationActive()) {
				store(redisKey, createdCourseId.toString(), RESULT_TTL);
			}
		}

	}

	/**
	 * @param idempotencyKey the raw header value; {@code null}/blank = no guard.
	 * @throws ConflictException if an identical request is still in progress.
	 */
	Begin begin(UUID tenantId, UUID actorId, String idempotencyKey) {
		if (idempotencyKey == null || idempotencyKey.isBlank()) {
			return Begin.NONE;
		}
		if (idempotencyKey.length() > MAX_KEY_LENGTH) {
			throw CourseRuleException.invalid("VALIDATION_ERROR", "Idempotency-Key",
					"Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
		}
		String redisKey = KEY_PREFIX + tenantId + ":" + actorId + ":" + sha256(idempotencyKey.strip());
		try {
			Boolean claimed = redisTemplate.opsForValue().setIfAbsent(redisKey, PENDING, PENDING_TTL);
			if (Boolean.TRUE.equals(claimed)) {
				return new Begin(null, new Claim(redisKey));
			}
			String existing = redisTemplate.opsForValue().get(redisKey);
			if (existing == null) {
				// Expired between the two calls - try once more.
				Boolean retried = redisTemplate.opsForValue().setIfAbsent(redisKey, PENDING, PENDING_TTL);
				return Boolean.TRUE.equals(retried) ? new Begin(null, new Claim(redisKey)) : Begin.NONE;
			}
			if (PENDING.equals(existing)) {
				throw new ConflictException("This course is already being created - please wait");
			}
			return new Begin(UUID.fromString(existing), null);
		}
		catch (DataAccessException | IllegalArgumentException ex) {
			log.warn("Course-create idempotency guard unavailable; creating without it", ex);
			return Begin.NONE;
		}
	}

	/** Forgets a stored result whose course no longer exists, so the caller can create again. */
	void forget(UUID tenantId, UUID actorId, String idempotencyKey) {
		release(KEY_PREFIX + tenantId + ":" + actorId + ":" + sha256(idempotencyKey.strip()));
	}

	private void store(String redisKey, String value, Duration ttl) {
		try {
			redisTemplate.opsForValue().set(redisKey, value, ttl);
		}
		catch (DataAccessException ex) {
			log.warn("Could not record course-create idempotency result", ex);
		}
	}

	private void release(String redisKey) {
		try {
			redisTemplate.delete(redisKey);
		}
		catch (DataAccessException ex) {
			log.warn("Could not release course-create idempotency claim", ex);
		}
	}

	static String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

}
