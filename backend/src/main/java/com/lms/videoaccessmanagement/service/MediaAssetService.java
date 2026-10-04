package com.lms.videoaccessmanagement.service;

import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.MediaUploadException;
import com.lms.common.error.NotFoundException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.error.ServiceUnavailableException;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.integrationmanagement.api.MediaConnectionApi;
import com.lms.integrationmanagement.api.VideoProviderUploadApi;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadFailure;
import com.lms.integrationmanagement.api.VideoProviderUploadApi.UploadMetadata;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.MediaPrivacyStatus;
import com.lms.videoaccessmanagement.domain.MediaUploadJob;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.repository.MediaUploadJobRepository;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Media Library (VIDEO Phase 2 reads, Phase 5 edit + delete).
 *
 * <p>Tenant isolation is structural: every read is a {@link Specification}
 * on {@link VideoAssetRepository}, which {@code TenantAwareRepositoryImpl}
 * scopes to the authenticated tenant - no tenant id is ever taken from the
 * request. On top of that, visibility inside the tenant:
 * <ul>
 * <li>Teacher / Teacher Assistant: their own uploads only (Q-010; Phase 6
 * widens this to media attached to their courses).</li>
 * <li>Everyone else needs {@code MATERIALS} (Q-011): VIEW to list, CREATE_EDIT
 * to edit, DELETE to delete. A Student has none of these and gets 403.</li>
 * </ul>
 * Soft-deleted assets are never listed, and an asset the caller may not see
 * is reported as not found (404), exactly like another tenant's asset.
 *
 * <p>Edits of a YouTube-hosted asset are sent to YouTube first (owner
 * decision, ADR-018 Amendment 3) with no transaction open, then stored and
 * audited. Delete is an LMS soft delete only - the YouTube copy stays
 * (Q-017).
 */
@Service
public class MediaAssetService {

	static final int MAX_PAGE_SIZE = 100;

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	/** Optional fields of {@code PATCH /media/{id}}; {@code null} = unchanged, blank description = cleared. */
	public record MediaAssetUpdate(String title, String description, MediaPrivacyStatus privacyStatus) {

	}

	private final VideoAssetRepository videoAssetRepository;

	private final MediaUploadJobRepository jobRepository;

	private final PermissionCheckService permissionCheckService;

	private final MediaAssetViews mediaAssetViews;

	private final MediaAssetLifecycleService lifecycle;

	private final MaterialLookupApi materialLookupApi;

	private final MediaConnectionApi connections;

	private final VideoProviderUploadApi providerUploads;

	public MediaAssetService(VideoAssetRepository videoAssetRepository, MediaUploadJobRepository jobRepository,
			PermissionCheckService permissionCheckService, MediaAssetViews mediaAssetViews,
			MediaAssetLifecycleService lifecycle, MaterialLookupApi materialLookupApi, MediaConnectionApi connections,
			VideoProviderUploadApi providerUploads) {
		this.videoAssetRepository = videoAssetRepository;
		this.jobRepository = jobRepository;
		this.permissionCheckService = permissionCheckService;
		this.mediaAssetViews = mediaAssetViews;
		this.lifecycle = lifecycle;
		this.materialLookupApi = materialLookupApi;
		this.connections = connections;
		this.providerUploads = providerUploads;
	}

	@Transactional(readOnly = true)
	public Page<MediaAssetView> list(MediaAssetListQuery query) {
		Specification<VideoAsset> spec = visibleToCaller(PermissionAction.VIEW).and(notDeleted());
		if (query.status() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), query.status()));
		}
		if (query.provider() != null) {
			spec = spec.and((root, q, cb) -> cb.equal(root.get("provider"), query.provider()));
		}
		if (query.search() != null && !query.search().isBlank()) {
			spec = spec.and(titleOrFilenameContains(query.search()));
		}
		int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
		PageRequest pageRequest = PageRequest.of(Math.max(query.page(), 0), size,
				Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
		Page<VideoAsset> page = videoAssetRepository.findAll(spec, pageRequest);
		List<UUID> ids = page.getContent().stream().map(VideoAsset::getId).toList();
		Map<UUID, MediaUploadJob> jobs = jobRepository.findLatestForAssets(ids);
		Set<UUID> attached = materialLookupApi.findVideoAssetIdsInUse(ids);
		return page.map(asset -> mediaAssetViews.toView(asset, jobs.get(asset.getId()),
				attached.contains(asset.getId())));
	}

	@Transactional(readOnly = true)
	public MediaAssetView get(UUID assetId) {
		VideoAsset asset = requireVisible(assetId, PermissionAction.VIEW);
		return enriched(asset);
	}

	/**
	 * Edits title / description / privacy. CREATED assets change in the LMS
	 * only (the worker sends them when the upload starts); assets already on
	 * YouTube change there first. While the file is being sent (UPLOADING /
	 * UPLOADED) the YouTube session already carries the old values: 409.
	 */
	public MediaAssetView update(UUID assetId, MediaAssetUpdate update) {
		VideoAsset asset = requireVisible(assetId, PermissionAction.CREATE_EDIT);
		MediaAssetUpdate merged = validateAndMerge(asset, update);
		if (asset.getProvider() != MediaAssetProvider.INTERNAL) {
			if (asset.getStatus() == VideoAssetStatus.UPLOADING || asset.getStatus() == VideoAssetStatus.UPLOADED) {
				throw MediaUploadException
					.stateConflict("This video is being sent to YouTube. Edit it once the upload has finished.");
			}
			if (asset.getProviderAssetId() != null) {
				pushToProvider(asset, merged);
			}
		}
		lifecycle.updateMetadata(assetId, merged.title(), merged.description(), merged.privacyStatus(),
				AuthenticatedPrincipalHolder.get().userId());
		return get(assetId);
	}

	/**
	 * Soft delete. Refused while a lesson material uses the asset or an
	 * upload is still running. The YouTube copy is kept (Q-017).
	 */
	public void delete(UUID assetId) {
		VideoAsset asset = requireVisible(assetId, PermissionAction.DELETE);
		if (jobRepository.findLatestForAsset(assetId).filter(MediaUploadJob::isLive).isPresent()) {
			throw MediaUploadException.stateConflict("This video is still uploading. Cancel the upload first.");
		}
		if (materialLookupApi.findVideoAssetIdsInUse(Set.of(asset.getId())).contains(asset.getId())) {
			throw new ProviderConflictException(ApiErrorCodes.MEDIA_ATTACHED,
					"This video is used in a lesson. Remove it from the lesson first.");
		}
		lifecycle.delete(assetId, AuthenticatedPrincipalHolder.get().userId());
	}

	// ------------------------------------------------------------------

	private MediaAssetView enriched(VideoAsset asset) {
		return mediaAssetViews.toView(asset, jobRepository.findLatestForAsset(asset.getId()).orElse(null),
				materialLookupApi.findVideoAssetIdsInUse(Set.of(asset.getId())).contains(asset.getId()));
	}

	private void pushToProvider(VideoAsset asset, MediaAssetUpdate merged) {
		UUID connectionId = connections.currentConnectionForSameChannel(asset.getProviderConnectionId())
			.orElseThrow(() -> new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_AUTH_EXPIRED,
					"Reconnect YouTube in Settings → Video Providers to edit this video."));
		try {
			providerUploads.updateMetadata(connectionId, asset.getProviderAssetId(),
					new UploadMetadata(merged.title(), merged.description(), merged.privacyStatus().name()));
		}
		catch (UploadFailure failure) {
			throw switch (failure.getKind()) {
				case SCOPE_MISSING -> new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_SCOPE_MISSING,
						"Reconnect YouTube in Settings → Video Providers to allow editing videos.");
				case AUTH -> new ProviderConflictException(ApiErrorCodes.VIDEO_PROVIDER_AUTH_EXPIRED,
						"Reconnect YouTube in Settings → Video Providers to edit this video.");
				case CHANNEL_UNAVAILABLE -> new ProviderConflictException(
						ApiErrorCodes.VIDEO_PROVIDER_DISABLED_BY_PLATFORM, failure.getMessage());
				case FATAL -> MediaUploadException.rejected(failure.getMessage());
				case RETRYABLE, QUOTA, SESSION_GONE -> new ServiceUnavailableException(
						"YouTube is not reachable right now. Try again in a moment.");
			};
		}
	}

	private static MediaAssetUpdate validateAndMerge(VideoAsset asset, MediaAssetUpdate update) {
		List<FieldError> errors = new ArrayList<>();
		if (update == null || (update.title() == null && update.description() == null
				&& update.privacyStatus() == null)) {
			throw new FieldValidationException("Nothing to change",
					List.of(new FieldError("title", "give at least one of title, description, privacyStatus")));
		}
		String title = asset.getTitle();
		if (update.title() != null) {
			title = update.title().trim();
			if (title.isEmpty() || title.length() > MediaUploadService.MAX_TITLE_LENGTH || hasAngleBrackets(title)) {
				errors.add(new FieldError("title", "must be 1 to " + MediaUploadService.MAX_TITLE_LENGTH
						+ " characters, without < or >"));
			}
		}
		String description = asset.getDescription();
		if (update.description() != null) {
			description = update.description().trim().isEmpty() ? null : update.description().trim();
			if (description != null && (description.length() > MediaUploadService.MAX_DESCRIPTION_LENGTH
					|| hasAngleBrackets(description))) {
				errors.add(new FieldError("description", "must be at most "
						+ MediaUploadService.MAX_DESCRIPTION_LENGTH + " characters, without < or >"));
			}
		}
		MediaPrivacyStatus privacy = asset.getPrivacyStatus();
		if (update.privacyStatus() != null) {
			if (asset.getProvider() == MediaAssetProvider.INTERNAL) {
				errors.add(new FieldError("privacyStatus", "does not apply to this media"));
			}
			else if (update.privacyStatus() == MediaPrivacyStatus.PUBLIC) {
				errors.add(new FieldError("privacyStatus", "must be UNLISTED or PRIVATE"));
			}
			else {
				privacy = update.privacyStatus();
			}
		}
		if (!errors.isEmpty()) {
			throw new FieldValidationException("Invalid media update", errors);
		}
		return new MediaAssetUpdate(title, description, privacy);
	}

	private VideoAsset requireVisible(UUID assetId, PermissionAction action) {
		Specification<VideoAsset> byId = (root, q, cb) -> cb.equal(root.get("id"), assetId);
		return videoAssetRepository.findOne(byId.and(visibleToCaller(action)).and(notDeleted()))
			.orElseThrow(() -> new NotFoundException("Media asset not found"));
	}

	private Specification<VideoAsset> visibleToCaller(PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			UUID userId = principal.userId();
			return (root, q, cb) -> cb.equal(root.get("uploadedBy"), userId);
		}
		permissionCheckService.requirePermission(DomainArea.MATERIALS, action);
		return (root, q, cb) -> cb.conjunction();
	}

	private static Specification<VideoAsset> notDeleted() {
		return (root, q, cb) -> cb.isNull(root.get("deletedAt"));
	}

	private static Specification<VideoAsset> titleOrFilenameContains(String search) {
		String pattern = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
		return (root, q, cb) -> cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
				cb.like(cb.lower(root.get("originalFilename")), pattern, '\\'));
	}

	private static String escapeLike(String raw) {
		return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static boolean hasAngleBrackets(String value) {
		return value.indexOf('<') >= 0 || value.indexOf('>') >= 0;
	}

	/** Media Library list filters; all optional except paging. */
	public record MediaAssetListQuery(VideoAssetStatus status, MediaAssetProvider provider, String search, int page,
			int size) {

	}

}
