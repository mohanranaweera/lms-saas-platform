package com.lms.videoaccessmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.MediaUploadException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.integrationmanagement.api.MediaConnectionApi.TenantChannelSummary;
import com.lms.integrationmanagement.api.MediaConnectionStatus;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.api.MediaStagingApi;
import com.lms.integrationmanagement.api.MediaStagingApi.CompletedPart;
import com.lms.integrationmanagement.api.MediaStagingApi.PresignedPart;
import com.lms.integrationmanagement.api.MediaStagingApi.StagingUpload;
import com.lms.integrationmanagement.api.VideoProviderUploadApi;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.config.VideoUploadProperties;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.MediaPrivacyStatus;
import com.lms.videoaccessmanagement.domain.MediaUploadJob;
import com.lms.videoaccessmanagement.domain.MediaUploadJobStatus;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.TenantVideoUsageDaily;
import com.lms.videoaccessmanagement.repository.TenantVideoUsageDailyRepository;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import com.lms.videoaccessmanagement.service.MediaUploadStore.Cleanup;
import com.lms.videoaccessmanagement.service.MediaUploadStore.CreatedUpload;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Starting, finishing, cancelling and retrying a YouTube upload (VIDEO Phase
 * 4, plan §13 / §28). The browser sends the file straight to S3 staging with
 * presigned part URLs; this service never touches video bytes except the
 * first 64 KiB it reads back to check the content type (security.md).
 *
 * <p>Who may upload: a Teacher / Teacher Assistant (their own uploads only,
 * like the Media Library), or anyone with {@code MATERIALS} CREATE_EDIT
 * (Tenant Admin, permitted staff). A Student never can. Assets are read
 * through the tenant filter; another tenant's or another teacher's asset is
 * a plain 404.
 *
 * <p>Deliberately NOT {@code @Transactional}: S3 calls happen with no
 * transaction open, and {@link MediaUploadStore} commits each outcome.
 */
@Service
public class MediaUploadService {

	private static final Logger securityLog = LoggerFactory.getLogger("SECURITY");

	static final Set<String> ALLOWED_MIME_TYPES = Set.of("video/mp4", "video/webm", "video/quicktime");

	static final int MAX_TITLE_LENGTH = 100;

	static final int MAX_DESCRIPTION_LENGTH = 5000;

	static final int MAX_FILENAME_LENGTH = 255;

	/** Part URLs handed out per response. */
	static final int MAX_PARTS_PER_REQUEST = 100;

	static final int SNIFF_BYTES = 64 * 1024;

	private static final int MAX_ETAG_LENGTH = 256;

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	public record CreateUploadCommand(String title, String description, MediaPrivacyStatus privacyStatus,
			MediaAssetProvider provider, String fileName, String mimeType, Long sizeBytes) {

	}

	public record UploadInstructions(long partSizeBytes, int partCount, List<PresignedPart> parts) {

	}

	public record StartedUpload(MediaAssetView mediaAsset, UploadInstructions upload) {

	}

	/** Channel state as the upload panel shows it. */
	public enum ChannelState {

		NOT_CONNECTED, CONNECTED, EXPIRED, DISABLED

	}

	/**
	 * What the upload panel needs before a file is chosen (VIDEO Phase 5).
	 *
	 * @param editingEnabled the channel's grant can edit videos (ADR-018 Amendment 3)
	 */
	public record UploadLimits(boolean uploadsEnabled, ChannelState channel, boolean editingEnabled, int dailyLimit,
			int usedToday, long maxFileSizeBytes, long partSizeBytes, Set<String> allowedMimeTypes, boolean canUpload,
			String notAllowedReason, UploadsDisabledBy uploadsDisabledBy, String uploadsDisabledReason) {

	}

	/** Which switch turned uploads off (null in the view when they are on). */
	public enum UploadsDisabledBy {

		/** The platform's {@code video.features.youtube-upload} flag. */
		PLATFORM,
		/** The institute's own "Video uploads" setting. */
		INSTITUTE

	}

	static final String PLATFORM_UPLOADS_OFF = "Video uploads aren't enabled on this platform yet. Contact platform support.";

	static final String INSTITUTE_UPLOADS_OFF =
			"Video uploads are switched off for your institute. A Tenant Admin can switch them on in Video settings.";

	private final MediaUploadStore store;

	private final VideoAssetRepository assets;

	private final MediaAssetViews views;

	private final MediaStagingApi staging;

	private final VideoProviderUploadApi providerUploads;

	private final MediaConnectionApi connections;

	private final TenantVideoConfigurationService configuration;

	private final VideoFeatureFlags featureFlags;

	private final VideoUploadProperties properties;

	private final PermissionCheckService permissionCheckService;

	private final TenantContext tenantContext;

	private final TenantVideoUsageDailyRepository usage;

	private final Clock clock;

	private final TenantVideoChannelService channelSettings;

	public MediaUploadService(MediaUploadStore store, VideoAssetRepository assets, MediaAssetViews views,
			MediaStagingApi staging, VideoProviderUploadApi providerUploads, MediaConnectionApi connections,
			TenantVideoConfigurationService configuration, VideoFeatureFlags featureFlags,
			VideoUploadProperties properties, PermissionCheckService permissionCheckService,
			TenantContext tenantContext, TenantVideoUsageDailyRepository usage, Clock clock,
			TenantVideoChannelService channelSettings) {
		this.store = store;
		this.assets = assets;
		this.views = views;
		this.staging = staging;
		this.providerUploads = providerUploads;
		this.connections = connections;
		this.configuration = configuration;
		this.featureFlags = featureFlags;
		this.properties = properties;
		this.permissionCheckService = permissionCheckService;
		this.tenantContext = tenantContext;
		this.usage = usage;
		this.clock = clock;
		this.channelSettings = channelSettings;
	}

	/** The upload panel's limits and channel state, for whoever may upload. */
	@Transactional(readOnly = true)
	public UploadLimits limits() {
		UUID callerId = requireUploader(false);
		boolean allowed = !isTeacher(AuthenticatedPrincipalHolder.get())
				|| channelSettings.isTeacherAllowedToUpload(callerId);
		Optional<TenantChannelSummary> channel = connections.findTenantVideoChannel(MediaProvider.YOUTUBE);
		ChannelState state = channel.map(c -> c.platformDisabled() ? ChannelState.DISABLED
				: (c.status() == MediaConnectionStatus.ACTIVE) ? ChannelState.CONNECTED : ChannelState.EXPIRED)
			.orElse(ChannelState.NOT_CONNECTED);
		int used = usage.findForDay(UploadSchedule.quotaDay(clock.instant()))
			.map(TenantVideoUsageDaily::getUploads)
			.orElse(0);
		UploadsDisabledBy disabledBy = uploadsDisabledBy();
		return new UploadLimits(disabledBy == null, state,
				channel.map(TenantChannelSummary::editScopeGranted).orElse(false),
				configuration.effectiveDailyUploadLimit(), used, properties.getMaxFileSizeBytes(),
				properties.getPartSizeBytes(), new TreeSet<>(ALLOWED_MIME_TYPES), allowed,
				allowed ? null : MediaUploadException.notAllowed().getMessage(), disabledBy,
				(disabledBy == null) ? null : disabledMessage(disabledBy));
	}

	/**
	 * Creates the asset and its staging upload; returns the first part URLs.
	 * Checks, in order: who may upload, platform flag + tenant switch, a
	 * usable tenant YouTube channel, the request itself, the daily budget.
	 */
	public StartedUpload create(CreateUploadCommand command) {
		UUID actorId = requireUploader(true);
		requireUploadsEnabled();
		UUID connectionId = requireUsableChannel();
		CreateUploadCommand valid = validate(command);

		VideoAsset newAsset = VideoAsset.createForProvider(tenantContext.getTenantId(), MediaAssetProvider.YOUTUBE,
				connectionId, valid.title(), valid.description(), valid.privacyStatus(), valid.fileName(),
				valid.mimeType(), valid.sizeBytes(), actorId);
		CreatedUpload created = store.createPending(newAsset, connectionId, configuration.effectiveDailyUploadLimit());
		MediaUploadJob job = created.job();
		StagingUpload upload;
		try {
			upload = staging.initiate(job.getStagingObjectKey(), valid.mimeType());
		}
		catch (RuntimeException ex) {
			store.abortCreation(job.getId(), "The upload could not be started. Try again.");
			throw ex;
		}
		store.attachStagingUpload(job.getId(), upload.uploadId());
		int partCount = job.partCount();
		List<Integer> first = IntStream.rangeClosed(1, Math.min(partCount, MAX_PARTS_PER_REQUEST)).boxed().toList();
		return new StartedUpload(views.toView(created.asset()), new UploadInstructions(job.getPartSizeBytes(),
				partCount, staging.presignParts(upload, first, properties.getPresignTtl())));
	}

	/** More part URLs (or fresh ones after they expired) for an upload still waiting for its parts. */
	public UploadInstructions presignParts(UUID assetId, List<Integer> partNumbers) {
		requireAccessibleAsset(assetId);
		MediaUploadJob job = store.requireLatestJob(assetId, MediaUploadJobStatus.AWAITING_PARTS);
		int partCount = job.partCount();
		List<FieldError> errors = new ArrayList<>();
		if (partNumbers == null || partNumbers.isEmpty() || partNumbers.size() > MAX_PARTS_PER_REQUEST) {
			errors.add(new FieldError("partNumbers", "must list 1 to " + MAX_PARTS_PER_REQUEST + " part numbers"));
		}
		else if (partNumbers.stream().anyMatch(n -> n == null || n < 1 || n > partCount)
				|| new HashSet<>(partNumbers).size() != partNumbers.size()) {
			errors.add(new FieldError("partNumbers", "must be distinct numbers between 1 and " + partCount));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid part numbers", errors);
		}
		return new UploadInstructions(job.getPartSizeBytes(), partCount,
				staging.presignParts(stagingUpload(job), partNumbers, properties.getPresignTtl()));
	}

	/**
	 * The browser has uploaded every part: assemble the staging object, check
	 * its size and real content type, then queue the transfer to YouTube.
	 *
	 * @throws MediaUploadException 422 (and the staged file is deleted) when
	 * the size or content does not match
	 */
	public MediaAssetView complete(UUID assetId, List<CompletedPart> parts) {
		requireAccessibleAsset(assetId);
		MediaUploadJob job = store.requireLatestJob(assetId, MediaUploadJobStatus.AWAITING_PARTS);
		validateParts(parts, job.partCount());
		staging.complete(stagingUpload(job), parts);

		long size = staging.size(job.getStagingObjectKey());
		if (size != job.getBytesTotal()) {
			throw reject(job, "The uploaded file is not the size that was announced. Upload it again.");
		}
		String detected = VideoContentSniffer.sniff(readHead(job.getStagingObjectKey(), size));
		if (detected == null || !ALLOWED_MIME_TYPES.contains(detected)) {
			throw reject(job, "This file is not a supported video. Upload an MP4, WebM or MOV file.");
		}
		return store.markQueued(job.getId(), detected);
	}

	/** Cancels the asset's live upload; the staged file and any YouTube session are discarded. */
	public MediaAssetView cancel(UUID assetId) {
		requireAccessibleAsset(assetId);
		Cleanup cleanup = store.cancel(assetId);
		discardStaging(cleanup);
		if (cleanup.sessionHandle() != null) {
			providerUploads.cancelSession(cleanup.connectionId(), cleanup.sessionHandle());
		}
		return cleanup.asset();
	}

	/** Re-queues a failed transfer whose staged file still exists (no new budget slot). */
	public MediaAssetView retry(UUID assetId) {
		VideoAsset asset = requireAccessibleAsset(assetId);
		requireTeacherAllowList();
		requireUploadsEnabled();
		requireUsableChannel();
		// The asset belongs to the channel it was created for; a replaced channel cannot take it over.
		UUID connectionId = connections.currentConnectionForSameChannel(asset.getProviderConnectionId())
			.orElseThrow(() -> MediaUploadException
				.stateConflict("The YouTube channel was changed since this upload started. Upload the file again."));
		return store.retry(assetId, connectionId);
	}

	// ------------------------------------------------------------------

	/**
	 * Teacher / TA, or {@code MATERIALS} CREATE_EDIT staff. With {@code
	 * enforceAllowList}, a teacher must also be on the institute channel's
	 * allowed-teachers list when the admin has set one (VIDEO Phase 5b).
	 */
	private UUID requireUploader(boolean enforceAllowList) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (!isTeacher(principal)) {
			permissionCheckService.requirePermission(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT);
		}
		else if (enforceAllowList) {
			requireTeacherAllowList();
		}
		return principal.userId();
	}

	private void requireTeacherAllowList() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (isTeacher(principal) && !channelSettings.isTeacherAllowedToUpload(principal.userId())) {
			securityLog.atInfo().setMessage("video_upload.teacher_not_allowed").log();
			throw MediaUploadException.notAllowed();
		}
	}

	/** 404 for another tenant's asset (tenant filter), another teacher's upload, or a deleted asset. */
	private VideoAsset requireAccessibleAsset(UUID assetId) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		boolean teacher = isTeacher(principal);
		if (!teacher) {
			permissionCheckService.requirePermission(DomainArea.MATERIALS, PermissionAction.CREATE_EDIT);
		}
		return assets.findById(assetId)
			.filter(asset -> !asset.isDeleted())
			.filter(asset -> asset.getProvider() != MediaAssetProvider.INTERNAL)
			.filter(asset -> !teacher || principal.userId().equals(asset.getUploadedBy()))
			.orElseThrow(() -> new NotFoundException("Media asset not found"));
	}

	private void requireUploadsEnabled() {
		UploadsDisabledBy disabledBy = uploadsDisabledBy();
		if (disabledBy != null) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_UPLOADS_DISABLED, disabledMessage(disabledBy));
		}
	}

	/** The platform flag wins: an institute can't switch on what the platform has off. */
	private UploadsDisabledBy uploadsDisabledBy() {
		if (!featureFlags.isYoutubeUpload()) {
			return UploadsDisabledBy.PLATFORM;
		}
		return configuration.effectiveSettings().uploadsEnabled() ? null : UploadsDisabledBy.INSTITUTE;
	}

	private static String disabledMessage(UploadsDisabledBy disabledBy) {
		return (disabledBy == UploadsDisabledBy.PLATFORM) ? PLATFORM_UPLOADS_OFF : INSTITUTE_UPLOADS_OFF;
	}

	private UUID requireUsableChannel() {
		TenantChannelSummary channel = connections.findTenantVideoChannel(MediaProvider.YOUTUBE)
			.orElseThrow(() -> new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_NOT_CONNECTED,
					"Video uploads aren't set up for your institute yet."));
		if (channel.platformDisabled()) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_DISABLED_BY_PLATFORM,
					"Video uploads were switched off by the platform for your institute.");
		}
		if (channel.status() != MediaConnectionStatus.ACTIVE) {
			throw new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_AUTH_EXPIRED,
					"Reconnect YouTube in Settings → Video Providers.");
		}
		return channel.connectionId();
	}

	private CreateUploadCommand validate(CreateUploadCommand c) {
		List<FieldError> errors = new ArrayList<>();
		String title = trimToNull(c.title());
		if (title == null || title.length() > MAX_TITLE_LENGTH || containsAngleBrackets(title)) {
			errors.add(new FieldError("title", "is required, at most " + MAX_TITLE_LENGTH
					+ " characters, without < or >"));
		}
		String description = trimToNull(c.description());
		if (description != null && (description.length() > MAX_DESCRIPTION_LENGTH
				|| containsAngleBrackets(description))) {
			errors.add(new FieldError("description", "must be at most " + MAX_DESCRIPTION_LENGTH
					+ " characters, without < or >"));
		}
		MediaPrivacyStatus privacy = (c.privacyStatus() == null)
				? configuration.effectiveSettings().defaultPrivacyStatus() : c.privacyStatus();
		if (privacy == MediaPrivacyStatus.PUBLIC) {
			errors.add(new FieldError("privacyStatus", "must be UNLISTED or PRIVATE"));
		}
		if (c.provider() != null && c.provider() != MediaAssetProvider.YOUTUBE) {
			errors.add(new FieldError("provider", "must be YOUTUBE"));
		}
		String fileName = trimToNull(c.fileName());
		if (fileName == null || fileName.length() > MAX_FILENAME_LENGTH) {
			errors.add(new FieldError("fileName", "is required, at most " + MAX_FILENAME_LENGTH + " characters"));
		}
		if (c.mimeType() == null || !ALLOWED_MIME_TYPES.contains(c.mimeType())) {
			errors.add(new FieldError("mimeType", "must be video/mp4, video/webm or video/quicktime"));
		}
		if (c.sizeBytes() == null || c.sizeBytes() < 1 || c.sizeBytes() > properties.getMaxFileSizeBytes()) {
			errors.add(new FieldError("sizeBytes", "must be between 1 and " + properties.getMaxFileSizeBytes()));
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid upload", errors);
		}
		return new CreateUploadCommand(title, description, privacy, MediaAssetProvider.YOUTUBE, fileName,
				c.mimeType(), c.sizeBytes());
	}

	private static void validateParts(List<CompletedPart> parts, int partCount) {
		boolean valid = parts != null && parts.size() == partCount;
		if (valid) {
			Set<Integer> seen = new HashSet<>();
			for (CompletedPart part : parts) {
				if (part == null || part.partNumber() < 1 || part.partNumber() > partCount
						|| !seen.add(part.partNumber()) || part.etag() == null || part.etag().isBlank()
						|| part.etag().length() > MAX_ETAG_LENGTH) {
					valid = false;
					break;
				}
			}
		}
		if (!valid) {
			throw new FieldValidationException("Invalid parts", List.of(new FieldError("parts",
					"must list every part 1 to " + partCount + " exactly once, each with its ETag")));
		}
	}

	private byte[] readHead(String objectKey, long size) {
		int length = (int) Math.min(SNIFF_BYTES, size);
		try (InputStream in = staging.openRange(objectKey, 0, length - 1)) {
			return in.readNBytes(length);
		}
		catch (IOException ex) {
			throw new ServiceUnavailableException("Uploads aren't available right now. Please try again later.");
		}
	}

	private MediaUploadException reject(MediaUploadJob job, String reason) {
		store.rejectStaged(job.getId(), reason);
		try {
			staging.delete(job.getStagingObjectKey());
		}
		catch (RuntimeException ex) {
			// The sweeper and the bucket lifecycle rule remove it later.
		}
		securityLog.atWarn()
			.setMessage("video_upload.staged_file_rejected")
			.addKeyValue("jobId", job.getId())
			.addKeyValue("reason", reason)
			.log();
		return MediaUploadException.rejected(reason);
	}

	private void discardStaging(Cleanup cleanup) {
		try {
			if (cleanup.stagingUploadId() != null) {
				staging.abort(new StagingUpload(cleanup.stagingObjectKey(), cleanup.stagingUploadId()));
			}
			staging.delete(cleanup.stagingObjectKey());
		}
		catch (RuntimeException ex) {
			// Best effort - the sweeper and the bucket lifecycle rule remove it later.
		}
	}

	private static StagingUpload stagingUpload(MediaUploadJob job) {
		if (job.getStagingUploadId() == null) {
			throw MediaUploadException.stateConflict("This upload was not started. Create it again.");
		}
		return new StagingUpload(job.getStagingObjectKey(), job.getStagingUploadId());
	}

	private static boolean isTeacher(AuthenticatedPrincipal principal) {
		return TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role());
	}

	private static boolean containsAngleBrackets(String value) {
		return value.indexOf('<') >= 0 || value.indexOf('>') >= 0;
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

}
