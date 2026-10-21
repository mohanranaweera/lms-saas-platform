package com.lms.enrollmentmanagement.repository;

import com.lms.common.persistence.AppendOnlyTenantAwareRepository;
import com.lms.enrollmentmanagement.domain.FreeCard;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped {@code free_card} access (ADR-030). Every finder is a {@code
 * default} method on the inherited, structurally tenant-filtered {@code
 * findAll}/{@code findOne} - no method accepts a tenant id. Rows are inserted
 * by {@code FreeCardService#issue}; afterwards only the revoke columns change
 * once ({@link FreeCard#revoke}). Deletes are refused (append-only).
 */
public interface FreeCardRepository extends AppendOnlyTenantAwareRepository<FreeCard, UUID> {

	/** Every NOT-revoked card of one student for one course (overlap check, evaluator). */
	default List<FreeCard> findUnrevokedByStudentIdAndCourseId(UUID studentId, UUID courseId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("courseId"), courseId), cb.isNull(root.get("revokedAt"))));
	}

	/** The card issued with this idempotency key in the current tenant, if any. */
	default Optional<FreeCard> findByIdempotencyKey(UUID idempotencyKey) {
		return findOne((root, query, cb) -> cb.equal(root.get("idempotencyKey"), idempotencyKey));
	}

	/**
	 * Admin list, newest first. Every filter is optional. {@code status} is
	 * evaluated at {@code now} with the same rules as {@link FreeCard#statusAt}.
	 */
	default Page<FreeCard> findPage(UUID studentId, UUID courseId, FreeCard.Status status, Instant now,
			Pageable pageable) {
		return findAll((root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (studentId != null) {
				predicates.add(cb.equal(root.get("studentId"), studentId));
			}
			if (courseId != null) {
				predicates.add(cb.equal(root.get("courseId"), courseId));
			}
			if (status != null) {
				switch (status) {
					case REVOKED -> predicates.add(cb.isNotNull(root.get("revokedAt")));
					case SCHEDULED -> predicates.add(cb.and(cb.isNull(root.get("revokedAt")),
							cb.greaterThan(root.<Instant>get("effectiveFrom"), now)));
					case ACTIVE -> predicates.add(cb.and(cb.isNull(root.get("revokedAt")),
							cb.lessThanOrEqualTo(root.<Instant>get("effectiveFrom"), now),
							cb.greaterThan(root.<Instant>get("effectiveUntil"), now)));
					case EXPIRED -> predicates.add(cb.and(cb.isNull(root.get("revokedAt")),
							cb.lessThanOrEqualTo(root.<Instant>get("effectiveUntil"), now)));
				}
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		}, pageable);
	}

	/**
	 * Transaction-scoped Postgres advisory lock on a caller-built key that
	 * already contains the context tenant ({@code freecard:<tenant>:<student>:<course>}
	 * or {@code freecard-key:<tenant>:<idempotencyKey>}). Serializes the F5
	 * overlap check and idempotent replays.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireLock(@Param("lockKey") String lockKey);

}
