package com.lms.videoaccessmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoPlaybackPolicy;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoPlaybackPolicyRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchProgressRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A video's own watch-time limit (VIDEO Phase 12, owner decision 2026-10-09):
 * set when the video is added to a lesson and editable later. When set it
 * wins over the institute-wide rule (higher or lower); null falls back to the
 * institute rule. A change applies to every Student from their next heartbeat
 * (their budget snapshots are cleared); time watched and staff grants are
 * untouched. Same ownership-aware gate as {@code PUT /videos/{id}/policy}:
 * the Teacher who owns the course, or staff with {@code MATERIALS}
 * CREATE_EDIT. Every change is audited ({@code video.watch_time_limit_changed}).
 */
@Service
public class VideoWatchTimeLimitService {

	/** 1 minute to 1000 hours. */
	static final int MIN_LIMIT_SECONDS = 60;

	static final int MAX_LIMIT_SECONDS = 3_600_000;

	public record LimitView(UUID videoAssetId, Integer videoLimitSeconds, Integer instituteDefaultSeconds,
			boolean instituteDefaultKnown, Integer effectiveSeconds) {
	}

	private final VideoAssetService videoAssetService;

	private final VideoAssetRepository assetRepository;

	private final VideoPlaybackPolicyRepository policyRepository;

	private final VideoWatchProgressRepository progressRepository;

	private final WatchTimeService watchTimeService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public VideoWatchTimeLimitService(VideoAssetService videoAssetService, VideoAssetRepository assetRepository,
			VideoPlaybackPolicyRepository policyRepository, VideoWatchProgressRepository progressRepository,
			WatchTimeService watchTimeService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.videoAssetService = videoAssetService;
		this.assetRepository = assetRepository;
		this.policyRepository = policyRepository;
		this.progressRepository = progressRepository;
		this.watchTimeService = watchTimeService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	@Transactional(readOnly = true)
	public LimitView get(UUID videoAssetId) {
		videoAssetService.requirePolicyAuthorization(videoAssetId);
		VideoAsset asset = requireAsset(videoAssetId);
		return view(asset, currentLimit(videoAssetId));
	}

	/** @param limitSeconds the video's own limit; null removes it (the institute rule applies). */
	@Transactional
	public LimitView set(UUID videoAssetId, Integer limitSeconds) {
		videoAssetService.requirePolicyAuthorization(videoAssetId);
		if (limitSeconds != null && (limitSeconds < MIN_LIMIT_SECONDS || limitSeconds > MAX_LIMIT_SECONDS)) {
			String message = "Enter a watch-time limit between 1 minute and 1000 hours, or leave it empty.";
			throw new FieldValidationException(message, List.of(new FieldError("limitSeconds", message)));
		}
		VideoAsset asset = requireAsset(videoAssetId);
		Integer before = currentLimit(videoAssetId);
		if (Objects.equals(before, limitSeconds)) {
			return view(asset, before);
		}
		VideoPlaybackPolicy policy = policyRepository.findByVideoAssetId(videoAssetId)
			.orElseGet(() -> policyRepository.save(new VideoPlaybackPolicy(tenantContext.getTenantId(), videoAssetId,
					// The defaults playback assumes for a video without rules
					// (VideoPlaybackSessionService#resolveEffectivePolicy).
					null, null, null, null, true, false, true, null)));
		policy.changeMaxWatchDurationSeconds(limitSeconds);
		policyRepository.save(policy);
		int resnapshotted = progressRepository.clearBudgetSnapshots(tenantContext.getTenantId(), videoAssetId);

		Map<String, Object> metadata = new LinkedHashMap<>();
		// Audit metadata can't hold nulls: "institute_rule" = the video had/has no limit of its own.
		metadata.put("before", before == null ? "institute_rule" : before);
		metadata.put("after", limitSeconds == null ? "institute_rule" : limitSeconds);
		metadata.put("studentsAffected", resnapshotted);
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(),
				"video.watch_time_limit_changed", "video_asset", videoAssetId, null, metadata));
		return view(asset, limitSeconds);
	}

	private LimitView view(VideoAsset asset, Integer videoLimit) {
		Integer instituteDefault;
		boolean known = true;
		try {
			instituteDefault = watchTimeService.instituteDefaultSeconds(asset);
		}
		catch (ConflictException durationUnknown) {
			instituteDefault = null;
			known = false;
		}
		Integer effective = videoLimit != null ? videoLimit : instituteDefault;
		return new LimitView(asset.getId(), videoLimit, instituteDefault, known, effective);
	}

	private Integer currentLimit(UUID videoAssetId) {
		return policyRepository.findByVideoAssetId(videoAssetId)
			.map(VideoPlaybackPolicy::getMaxWatchDurationSeconds)
			.orElse(null);
	}

	private VideoAsset requireAsset(UUID videoAssetId) {
		return assetRepository.findById(videoAssetId).orElseThrow(() -> new NotFoundException("Video asset not found"));
	}

}
