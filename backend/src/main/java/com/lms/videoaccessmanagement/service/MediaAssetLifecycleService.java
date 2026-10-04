package com.lms.videoaccessmanagement.service;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.domain.VideoAssetStatusEvent;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoAssetStatusEventRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only path that changes a {@link VideoAsset}'s processing status: it
 * enforces {@link VideoAssetStatus#canTransitionTo} and writes the matching
 * append-only {@link VideoAssetStatusEvent} in the same transaction, so the
 * history can never drift from the asset row.
 *
 * <p>Callers (upload worker and status sync from Phase 4, delete from Phase
 * 5) run with the asset's tenant already in {@code TenantContext} - for jobs,
 * taken from the claimed row, never from a payload a client controls. The
 * repository's tenant filter makes a foreign asset id indistinguishable from
 * a missing one.
 */
@Service
public class MediaAssetLifecycleService {

	private final VideoAssetRepository videoAssetRepository;

	private final VideoAssetStatusEventRepository statusEventRepository;

	private final MediaAssetViews mediaAssetViews;

	private final Clock clock;

	public MediaAssetLifecycleService(VideoAssetRepository videoAssetRepository,
			VideoAssetStatusEventRepository statusEventRepository, MediaAssetViews mediaAssetViews, Clock clock) {
		this.videoAssetRepository = videoAssetRepository;
		this.statusEventRepository = statusEventRepository;
		this.mediaAssetViews = mediaAssetViews;
		this.clock = clock;
	}

	/**
	 * Persists a new asset together with its creation event ({@code null ->
	 * status}) in one transaction. Callers must not hold an outbound call
	 * open around this (backend.md transaction rule).
	 */
	@Transactional
	public VideoAsset create(VideoAsset newAsset) {
		VideoAsset asset = videoAssetRepository.save(newAsset);
		statusEventRepository.save(new VideoAssetStatusEvent(asset.getTenantId(), asset.getId(), null,
				asset.getStatus(), null, actorIdFor(asset), clock.instant()));
		return asset;
	}

	/**
	 * Moves the asset to {@code target}. {@code reason} is stored on the event
	 * and, for {@code FAILED}, shown as the asset's failure reason.
	 *
	 * @throws NotFoundException if the asset does not exist in the current tenant
	 * @throws ConflictException on an illegal transition (including {@code DELETED},
	 * which has its own path)
	 */
	@Transactional
	public MediaAssetView transition(UUID assetId, VideoAssetStatus target, String reason) {
		VideoAsset asset = videoAssetRepository.findById(assetId)
			.orElseThrow(() -> new NotFoundException("Media asset not found"));
		VideoAssetStatus from = asset.getStatus();
		try {
			if (target == VideoAssetStatus.FAILED) {
				asset.markFailed(reason);
			}
			else {
				asset.transitionTo(target);
			}
		}
		catch (IllegalStateException e) {
			throw new ConflictException("Media asset cannot move from " + from + " to " + target);
		}
		asset = videoAssetRepository.save(asset);
		statusEventRepository.save(new VideoAssetStatusEvent(asset.getTenantId(), asset.getId(), from, target,
				reason, actorIdFor(asset), clock.instant()));
		return mediaAssetViews.toView(asset);
	}

	/**
	 * The acting tenant user, or {@code null} for a system transition. A
	 * principal from another tenant (e.g. a platform admin) is never recorded,
	 * because the event's actor FK only accepts users of the asset's tenant.
	 */
	private static UUID actorIdFor(VideoAsset asset) {
		if (!AuthenticatedPrincipalHolder.isSet()) {
			return null;
		}
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		return asset.getTenantId().equals(principal.tenantId()) ? principal.userId() : null;
	}

}
