package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.videoaccessmanagement.domain.PlayerPairingCode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped (ADR-006) repository for one-time pairing codes (V77, VIDEO
 * Phase 8).
 *
 * <p>{@link #findByCodeHashAcrossTenantsForUpdate} is the ONE deliberate
 * exception to tenant scoping (ADR-025 Amendment 1, ADR-011 pattern, mirroring
 * {@code PaymentRepository#findByGatewayReferenceAcrossTenants}): {@code POST
 * /api/v1/player/pairings/complete} has no session or tenant host, so it finds
 * the row by the globally unique code hash first, then the caller sets the
 * tenant context to THAT row's {@code tenant_id}. The row lock serializes two
 * concurrent attempts with the same code, so only one can consume it.
 */
public interface PlayerPairingCodeRepository extends TenantAwareRepository<PlayerPairingCode, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM PlayerPairingCode c WHERE c.codeHash = :codeHash")
	Optional<PlayerPairingCode> findByCodeHashAcrossTenantsForUpdate(@Param("codeHash") String codeHash);

}
