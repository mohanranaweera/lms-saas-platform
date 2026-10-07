package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.videoaccessmanagement.domain.PlayerInstallation;
import com.lms.videoaccessmanagement.domain.PlayerInstallationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;

/** Tenant-scoped (ADR-006) repository for paired AOC Players (V77, VIDEO Phase 8). Rows are never deleted. */
public interface PlayerInstallationRepository extends TenantAwareRepository<PlayerInstallation, UUID> {

	default Optional<PlayerInstallation> findActiveForDevice(UUID deviceId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("deviceId"), deviceId),
				cb.equal(root.get("status"), PlayerInstallationStatus.ACTIVE)));
	}

	default List<PlayerInstallation> findAllForUser(UUID userId) {
		return findAll((root, query, cb) -> cb.equal(root.get("userId"), userId),
				Sort.by(Sort.Direction.DESC, "pairedAt"));
	}

	default Optional<PlayerInstallation> findForUser(UUID id, UUID userId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("id"), id), cb.equal(root.get("userId"), userId)));
	}

	@Override
	default void delete(PlayerInstallation entity) {
		throw new UnsupportedOperationException("player_installation rows are never deleted - revoke instead");
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("player_installation rows are never deleted - revoke instead");
	}

}
