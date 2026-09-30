package com.lms.identityaccessservice.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.identityaccessservice.domain.PasswordToken;
import com.lms.identityaccessservice.api.PasswordTokenPurpose;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006 (AUTH-PWD-01). The inherited Specification-backed
 * finders are scoped by {@code TenantAwareRepositoryImpl}.
 */
public interface PasswordTokenRepository extends TenantAwareRepository<PasswordToken, UUID> {

	/** Read-only lookup for "verify" (no lock). */
	default Optional<PasswordToken> findByTokenHash(String tokenHash) {
		return findOne((root, query, cb) -> cb.equal(root.get("tokenHash"), tokenHash));
	}

	/**
	 * Locked lookup for "complete", so two concurrent completions of the same
	 * token serialize and only the first finds it open. {@code tenantId} is
	 * explicit only because a custom {@code @Query} isn't composed with the
	 * tenant predicate (same as {@code PaymentRepository
	 * #findByIdAndTenantIdForUpdate}); callers pass the request's resolved
	 * {@code TenantContext} value, never client input.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM PasswordToken t WHERE t.tokenHash = :tokenHash AND t.tenantId = :tenantId")
	Optional<PasswordToken> findByTokenHashAndTenantIdForUpdate(@Param("tokenHash") String tokenHash,
			@Param("tenantId") UUID tenantId);

	/** A user's unused, unsuperseded tokens of one purpose (may include expired ones). */
	default List<PasswordToken> findUnconsumed(UUID userId, PasswordTokenPurpose purpose) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("userId"), userId),
				cb.equal(root.get("purpose"), purpose), cb.isNull(root.get("usedAt")),
				cb.isNull(root.get("invalidatedAt"))));
	}

	/** Every unconsumed token of a user, any purpose - superseded when the password changes. */
	default List<PasswordToken> findAllUnconsumed(UUID userId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("userId"), userId),
				cb.isNull(root.get("usedAt")), cb.isNull(root.get("invalidatedAt"))));
	}

	/** The newest token of a user for a purpose (admin panel: invited at / expires at). */
	default Optional<PasswordToken> findLatest(UUID userId, PasswordTokenPurpose purpose) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("userId"), userId),
				cb.equal(root.get("purpose"), purpose)), Sort.by(Sort.Direction.DESC, "createdAt"))
			.stream()
			.findFirst();
	}

	/** Created since {@code since} for a user and purpose (resend throttle). */
	default long countCreatedSince(UUID userId, PasswordTokenPurpose purpose, Instant since) {
		return count((root, query, cb) -> cb.and(cb.equal(root.get("userId"), userId),
				cb.equal(root.get("purpose"), purpose), cb.greaterThanOrEqualTo(root.get("createdAt"), since)));
	}

}
