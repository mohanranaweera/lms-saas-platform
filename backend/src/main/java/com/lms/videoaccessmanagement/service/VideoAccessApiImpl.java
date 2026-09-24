package com.lms.videoaccessmanagement.service;

import com.lms.videoaccessmanagement.api.VideoAccessApi;
import com.lms.videoaccessmanagement.api.VideoAssetSummary;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only cross-module-visible implementation of {@link VideoAccessApi} -
 * mirrors {@code contentmanagement.material.service.MaterialLookupApiImpl}'s
 * package placement exactly (an {@code api}-interface implementation lives
 * in its own domain's {@code service} package, never in {@code api} itself).
 *
 * <p>Relies on {@code VideoAssetRepository}'s inherited, structural
 * tenant-scoped {@code findById} - so a cross-tenant {@code videoAssetId}
 * simply resolves to {@link Optional#empty()} here, exactly like any other
 * tenant-scoped lookup, never a caller-supplied-tenant-id parameter.
 */
@Component
public class VideoAccessApiImpl implements VideoAccessApi {

	private final VideoAssetRepository videoAssetRepository;

	public VideoAccessApiImpl(VideoAssetRepository videoAssetRepository) {
		this.videoAssetRepository = videoAssetRepository;
	}

	@Override
	public boolean isVideoAssetReadyAndOwnedByTenant(UUID videoAssetId, UUID tenantId) {
		return videoAssetRepository.findById(videoAssetId)
			.filter(asset -> asset.getTenantId().equals(tenantId))
			.map(asset -> asset.getStatus() == VideoAssetStatus.READY)
			.orElse(false);
	}

	@Override
	public Optional<VideoAssetSummary> getSummary(UUID videoAssetId, UUID tenantId) {
		return videoAssetRepository.findById(videoAssetId)
			.filter(asset -> asset.getTenantId().equals(tenantId))
			.map(VideoAccessApiImpl::toSummary);
	}

	private static VideoAssetSummary toSummary(VideoAsset asset) {
		return new VideoAssetSummary(asset.getId(), asset.getStatus().name(), asset.getDurationSeconds());
	}

}
