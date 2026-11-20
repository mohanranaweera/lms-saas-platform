package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.videoaccessmanagement.domain.OneTimeCodeStatus;
import com.lms.videoaccessmanagement.domain.PlaybackLaunchCode;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped (ADR-006) repository for one-time launch codes (V77, VIDEO
 * Phase 8).
 *
 * <p>{@link #findByCodeHashAcrossTenantsForUpdate} is the ONE deliberate
 * exception to tenant scoping (ADR-025 §4, ADR-011 pattern): {@code POST
 * /api/v1/player/exchange} has no session or tenant host, so it finds the row
 * by the globally unique code hash, then sets the tenant context to THAT row's
 * {@code tenant_id}. The row lock serializes concurrent exchanges of the same
 * code, so exactly one can consume it.
 */
public interface PlaybackLaunchCodeRepository extends TenantAwareRepository<PlaybackLaunchCode, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM PlaybackLaunchCode c WHERE c.codeHash = :codeHash")
	Optional<PlaybackLaunchCode> findByCodeHashAcrossTenantsForUpdate(@Param("codeHash") String codeHash);

	default List<PlaybackLaunchCode> findIssuedForSession(UUID playbackSessionId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("playbackSessionId"), playbackSessionId),
				cb.equal(root.get("status"), OneTimeCodeStatus.ISSUED)));
	}

}
