package com.lms.videoaccessmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.common.error.ConflictException;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.usermanagement.api.StudentLookupApi;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoWatchProgress;
import com.lms.videoaccessmanagement.domain.VideoWatchTimeGrant;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchProgressRepository;
import com.lms.videoaccessmanagement.repository.VideoWatchTimeGrantRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Staff view of a Student's watch time and extra-time grants (VIDEO Phase 12,
 * V80). {@code DomainArea.ACCESS_EXPIRY}: VIEW to read, CREATE_EDIT to grant -
 * extra watch time is an access extension, so every grant is audited with its
 * reason and the before/after allowed time. Grants are append-only; time
 * watched is never reduced. The Student id is the student profile id (as in
 * {@code /api/v1/students/{studentId}/devices}); ids from another tenant are 404.
 */
@Service
public class WatchTimeGrantService {

	static final int MAX_EXTRA_MINUTES = 6000;

	private static final int MAX_REASON_LENGTH = 500;

	/** {@code NONE}: no limit applies; {@code UNKNOWN}: the limit needs the video's duration, not known yet. */
	public enum LimitStatus {
		LIMITED, NONE, UNKNOWN
	}

	public record GrantView(UUID id, int extraSeconds, String reason, UUID grantedBy, Instant grantedAt) {
	}

	public record VideoWatchTimeView(UUID videoAssetId, String title, LimitStatus limit, int usedSeconds,
			Integer baseSeconds, int grantedSeconds, Integer allowedSeconds, Integer remainingSeconds,
			boolean exhausted, Instant lastWatchedAt, List<GrantView> grants) {
	}

	private final PermissionCheckService permissionCheckService;

	private final StudentLookupApi studentLookupApi;

	private final VideoWatchProgressRepository progressRepository;

	private final VideoWatchTimeGrantRepository grantRepository;

	private final VideoAssetRepository assetRepository;

	private final WatchTimeService watchTimeService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public WatchTimeGrantService(PermissionCheckService permissionCheckService, StudentLookupApi studentLookupApi,
			VideoWatchProgressRepository progressRepository, VideoWatchTimeGrantRepository grantRepository,
			VideoAssetRepository assetRepository, WatchTimeService watchTimeService, AuditLogApi auditLogApi,
			TenantContext tenantContext) {
		this.permissionCheckService = permissionCheckService;
		this.studentLookupApi = studentLookupApi;
		this.progressRepository = progressRepository;
		this.grantRepository = grantRepository;
		this.assetRepository = assetRepository;
		this.watchTimeService = watchTimeService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	/** Every video the Student has started, with used/allowed time and grant history. */
	@Transactional(readOnly = true)
	public List<VideoWatchTimeView> list(UUID studentProfileId) {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.VIEW);
		UUID studentId = requireStudentUserId(studentProfileId);
		Map<UUID, List<GrantView>> grantsByAsset = grantRepository.findForStudentNewestFirst(studentId)
			.stream()
			.collect(Collectors.groupingBy(VideoWatchTimeGrant::getVideoAssetId, LinkedHashMap::new,
					Collectors.mapping(WatchTimeGrantService::toView, Collectors.toList())));
		return progressRepository.findAllForStudent(studentId)
			.stream()
			.flatMap(progress -> assetRepository.findById(progress.getVideoAssetId())
				.map(asset -> view(asset, studentId, progress, grantsByAsset.getOrDefault(asset.getId(), List.of())))
				.stream())
			.toList();
	}

	@Transactional
	public VideoWatchTimeView grant(UUID studentProfileId, UUID videoAssetId, Integer extraMinutes, String reason) {
		permissionCheckService.requirePermission(DomainArea.ACCESS_EXPIRY, PermissionAction.CREATE_EDIT);
		int minutes = requireMinutes(extraMinutes);
		String checkedReason = requireReason(reason);
		UUID studentId = requireStudentUserId(studentProfileId);
		VideoAsset asset = assetRepository.findById(videoAssetId)
			.orElseThrow(() -> new NotFoundException("Video not found"));
		WatchTimeService.Allowance before;
		try {
			before = watchTimeService.allowance(asset, studentId);
		}
		catch (ConflictException durationUnknown) {
			throw new ConflictException(
					"This video's watch-time limit isn't known yet (its length is still being read). Try again later.");
		}
		if (before.baseSeconds() == null) {
			throw new ConflictException("No watch-time limit applies to this video, so there is no time to add.");
		}
		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		VideoWatchTimeGrant grant = grantRepository.save(new VideoWatchTimeGrant(tenantContext.getTenantId(),
				videoAssetId, studentId, minutes * 60, checkedReason, actorId, Instant.now()));
		WatchTimeService.Allowance after = new WatchTimeService.Allowance(before.baseSeconds(),
				before.grantedSeconds() + grant.getExtraSeconds(), before.usedSeconds());

		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("videoAssetId", videoAssetId.toString());
		metadata.put("studentUserId", studentId.toString());
		metadata.put("grantId", grant.getId().toString());
		metadata.put("extraSeconds", grant.getExtraSeconds());
		metadata.put("usedSeconds", before.usedSeconds());
		metadata.put("before", before.allowedSeconds());
		metadata.put("after", after.allowedSeconds());
		auditLogApi.record(new AuditLogEntry(actorId, "video.watch_time_granted", "student_profile", studentProfileId,
				checkedReason, metadata));

		VideoWatchProgress progress = progressRepository.findByVideoAssetIdAndStudentId(videoAssetId, studentId)
			.orElse(null);
		List<GrantView> grants = grantRepository.findForStudentNewestFirst(studentId)
			.stream()
			.filter(candidate -> candidate.getVideoAssetId().equals(videoAssetId))
			.map(WatchTimeGrantService::toView)
			.toList();
		return view(asset, studentId, progress, grants);
	}

	private VideoWatchTimeView view(VideoAsset asset, UUID studentId, VideoWatchProgress progress,
			List<GrantView> grants) {
		WatchTimeService.Allowance allowance;
		LimitStatus limit;
		try {
			allowance = watchTimeService.allowance(asset, studentId);
			limit = allowance.baseSeconds() == null ? LimitStatus.NONE : LimitStatus.LIMITED;
		}
		catch (ConflictException durationUnknown) {
			int granted = grants.stream().mapToInt(GrantView::extraSeconds).sum();
			allowance = new WatchTimeService.Allowance(null, granted,
					progress == null ? 0 : progress.getAuthorizedWatchSeconds());
			limit = LimitStatus.UNKNOWN;
		}
		return new VideoWatchTimeView(asset.getId(), displayTitle(asset), limit, allowance.usedSeconds(),
				allowance.baseSeconds(), allowance.grantedSeconds(), allowance.allowedSeconds(),
				allowance.remainingSeconds(), allowance.exhausted(),
				progress == null ? null : progress.getLastWatchedAt(), grants);
	}

	/** Platform-storage uploads have no title of their own; their file name is what staff recognise. */
	static String displayTitle(VideoAsset asset) {
		if (StringUtils.hasText(asset.getTitle())) {
			return asset.getTitle();
		}
		return StringUtils.hasText(asset.getOriginalFilename()) ? asset.getOriginalFilename() : null;
	}

	private UUID requireStudentUserId(UUID studentProfileId) {
		return studentLookupApi.resolveUserId(studentProfileId)
			.orElseThrow(() -> new NotFoundException("Student not found"));
	}

	private static GrantView toView(VideoWatchTimeGrant grant) {
		return new GrantView(grant.getId(), grant.getExtraSeconds(), grant.getReason(), grant.getGrantedBy(),
				grant.getGrantedAt());
	}

	private static int requireMinutes(Integer extraMinutes) {
		if (extraMinutes == null || extraMinutes < 1 || extraMinutes > MAX_EXTRA_MINUTES) {
			String message = "Enter the extra time in minutes (1 to " + MAX_EXTRA_MINUTES + ").";
			throw new FieldValidationException(message, List.of(new FieldError("extraMinutes", message)));
		}
		return extraMinutes;
	}

	private static String requireReason(String reason) {
		if (!StringUtils.hasText(reason) || reason.trim().length() > MAX_REASON_LENGTH) {
			throw new FieldValidationException("Enter a reason (up to 500 characters).",
					List.of(new FieldError("reason", "Enter a reason (up to 500 characters).")));
		}
		return reason.trim();
	}

}
