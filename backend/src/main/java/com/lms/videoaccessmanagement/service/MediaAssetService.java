package com.lms.videoaccessmanagement.service;

import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.VideoAsset;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.repository.VideoAssetRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped Media Library reads (VIDEO Phase 2).
 *
 * <p>Tenant isolation is structural: every read is a {@link Specification}
 * on {@link VideoAssetRepository}, which {@code TenantAwareRepositoryImpl}
 * scopes to the authenticated tenant - no tenant id is ever taken from the
 * request. On top of that, visibility inside the tenant:
 * <ul>
 * <li>Teacher / Teacher Assistant: their own uploads only (plan Q-010 widens
 * this to media attached to their courses once lesson media lands).</li>
 * <li>Everyone else needs {@code MATERIALS}/{@code VIEW} (Tenant Admin and
 * permitted staff) and sees the whole tenant library; a Student has no such
 * grant and gets 403.</li>
 * </ul>
 * Soft-deleted assets are never listed, and an asset the caller may not see
 * is reported as not found (404), exactly like another tenant's asset.
 */
@Service
public class MediaAssetService {

	static final int MAX_PAGE_SIZE = 100;

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	private final VideoAssetRepository videoAssetRepository;

	private final PermissionCheckService permissionCheckService;

	private final MediaAssetViews mediaAssetViews;

	public MediaAssetService(VideoAssetRepository videoAssetRepository,
			PermissionCheckService permissionCheckService, MediaAssetViews mediaAssetViews) {
		this.videoAssetRepository = videoAssetRepository;
		this.permissionCheckService = permissionCheckService;
		this.mediaAssetViews = mediaAssetViews;
	}

	@Transactional(readOnly = true)
	public Page<MediaAssetView> list(MediaAssetListQuery query) {
		Specification<VideoAsset> spec = visibleToCaller().and(notDeleted());
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
		return videoAssetRepository.findAll(spec, pageRequest).map(mediaAssetViews::toView);
	}

	@Transactional(readOnly = true)
	public MediaAssetView get(UUID assetId) {
		Specification<VideoAsset> byId = (root, q, cb) -> cb.equal(root.get("id"), assetId);
		return videoAssetRepository.findOne(byId.and(visibleToCaller()).and(notDeleted()))
			.map(mediaAssetViews::toView)
			.orElseThrow(() -> new NotFoundException("Media asset not found"));
	}

	private Specification<VideoAsset> visibleToCaller() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		if (TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role())) {
			UUID userId = principal.userId();
			return (root, q, cb) -> cb.equal(root.get("uploadedBy"), userId);
		}
		permissionCheckService.requirePermission(DomainArea.MATERIALS, PermissionAction.VIEW);
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

	/** Media Library list filters; all optional except paging. */
	public record MediaAssetListQuery(VideoAssetStatus status, MediaAssetProvider provider, String search, int page,
			int size) {
	}

}
