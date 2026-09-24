package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.videoaccessmanagement.domain.VideoPlaybackPolicy;
import java.util.Optional;
import java.util.UUID;

/** Tenant-scoped per ADR-006, mirroring {@code MaterialRepository}'s style exactly. */
public interface VideoPlaybackPolicyRepository extends TenantAwareRepository<VideoPlaybackPolicy, UUID> {

	/**
	 * At most one row per asset ({@code uq_video_playback_policy_asset}, V51)
	 * - a missing row means "platform default policy" applied in code (plan
	 * §3/§4).
	 */
	default Optional<VideoPlaybackPolicy> findByVideoAssetId(UUID videoAssetId) {
		return findOne((root, query, cb) -> cb.equal(root.get("videoAssetId"), videoAssetId));
	}

}
