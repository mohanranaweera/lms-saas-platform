package com.lms.contentmanagement.library.support;

import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.library.domain.MediaFolder;
import com.lms.contentmanagement.library.domain.MediaFolderAccessLevel;
import com.lms.contentmanagement.library.domain.MediaFolderGrant;
import com.lms.contentmanagement.library.repository.MediaFolderGrantRepository;
import com.lms.contentmanagement.library.repository.MediaFolderRepository;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Media Library folder access policy (MEDIA-FOLDERS-01 section 4). Explicit per-folder grants,
 * no inheritance:
 * <ul>
 * <li><b>Tenant-wide folder authority</b> = staff other than Teacher / Teacher Assistant holding
 * {@code MEDIA_MANAGE_FOLDERS} or {@code MEDIA_SHARE_FOLDERS} (Tenant Admin by default): sees
 * every folder.</li>
 * <li>Everyone else: per folder OWNER / grant UPLOAD / grant VIEW / nothing.</li>
 * </ul>
 * {@code canView} = any relation; {@code canUpload} = Add Media and (OWNER, TENANT or UPLOAD);
 * {@code canManage} = Manage capability and (OWNER or TENANT); {@code canShare} = Share
 * capability and (OWNER or TENANT); {@code canTransfer} = both. Grants never give management
 * rights. Library VIEW itself is checked by {@link MediaFileAccessGuard} before any of this.
 *
 * <p>Tenant isolation is structural: every read goes through the tenant-aware repositories, so
 * another tenant's folder simply does not exist here (404). The caller identity and role come
 * from the authenticated principal only.
 */
@Component
public class MediaFolderAccess {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT";

	private final MediaFolderRepository folderRepository;

	private final MediaFolderGrantRepository grantRepository;

	private final PermissionCheckService permissionCheckService;

	private final TenantContext tenantContext;

	public MediaFolderAccess(MediaFolderRepository folderRepository, MediaFolderGrantRepository grantRepository,
			PermissionCheckService permissionCheckService, TenantContext tenantContext) {
		this.folderRepository = folderRepository;
		this.grantRepository = grantRepository;
		this.permissionCheckService = permissionCheckService;
		this.tenantContext = tenantContext;
	}

	/**
	 * Takes the tenant's media-folders advisory lock (MEDIA-FOLDERS-01 section 5) for the rest of
	 * the current transaction. Every folder-tree / grant mutation, file move and upload into a
	 * folder takes it first and then re-reads and re-checks what it depends on.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void lockTree() {
		folderRepository.acquireTreeLock("media-folders:" + tenantContext.getTenantId());
	}

	/** The current caller's folder-relevant capabilities (computed once per operation). */
	public Caller caller() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		boolean teacherish = TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role());
		boolean manage = permissionCheckService.hasCapability(Capability.MEDIA_MANAGE_FOLDERS);
		boolean share = permissionCheckService.hasCapability(Capability.MEDIA_SHARE_FOLDERS);
		// A teacher's library access is not matrix-based (MediaFileAccessGuard): they may always add.
		boolean addMedia = teacherish || permissionCheckService.hasCapability(Capability.MEDIA_ADD);
		return new Caller(principal.userId(), !teacherish && (manage || share), manage, share, addMedia);
	}

	/** Rights of {@code caller} on {@code folder}, given the caller's grant level on it ({@code null} = none). */
	public FolderRights rights(Caller caller, MediaFolder folder, MediaFolderAccessLevel grantLevel) {
		MediaFolderRelation relation;
		if (caller.userId().equals(folder.getOwnerUserId())) {
			relation = MediaFolderRelation.OWNER;
		}
		else if (caller.tenantWide()) {
			relation = MediaFolderRelation.TENANT;
		}
		else if (grantLevel == MediaFolderAccessLevel.UPLOAD) {
			relation = MediaFolderRelation.UPLOAD;
		}
		else if (grantLevel == MediaFolderAccessLevel.VIEW) {
			relation = MediaFolderRelation.VIEW;
		}
		else {
			return FolderRights.NONE;
		}
		boolean authority = relation == MediaFolderRelation.OWNER || relation == MediaFolderRelation.TENANT;
		boolean canManage = caller.holdsManage() && authority;
		boolean canShare = caller.holdsShare() && authority;
		boolean canUpload = caller.addMedia() && (authority || relation == MediaFolderRelation.UPLOAD);
		return new FolderRights(relation, true, canUpload, canManage, canShare, canManage && canShare);
	}

	/** Rights of {@code caller} on {@code folder} (reads the caller's grant on it). */
	public FolderRights rights(Caller caller, MediaFolder folder) {
		MediaFolderAccessLevel level = grantRepository.findByFolderIdAndGrantee(folder.getId(), caller.userId())
			.map(MediaFolderGrant::getAccessLevel)
			.orElse(null);
		return rights(caller, folder, level);
	}

	/** The folder when it exists in this tenant and {@code caller} may view it; otherwise 404. */
	public MediaFolder requireViewable(Caller caller, UUID folderId) {
		MediaFolder folder = folderRepository.findById(folderId).orElseThrow(MediaFolderAccess::folderNotFound);
		if (!rights(caller, folder).canView()) {
			throw folderNotFound();
		}
		return folder;
	}

	/** May {@code caller} view files filed in {@code folderId}? ({@code null} = Unfiled = always.) */
	public boolean canViewFolder(Caller caller, UUID folderId) {
		if (folderId == null) {
			return true;
		}
		return folderRepository.findById(folderId).map(folder -> rights(caller, folder).canView()).orElse(false);
	}

	/**
	 * Ids of every folder {@code caller} may view, or {@code null} for tenant-wide authority (every
	 * folder) - the file-visibility filter for lists, search, counts and uploader options.
	 */
	public Set<UUID> visibleFolderIdsOrNull(Caller caller) {
		if (caller.tenantWide()) {
			return null;
		}
		Set<UUID> ids = new HashSet<>();
		folderRepository.findOwnedBy(caller.userId()).forEach(folder -> ids.add(folder.getId()));
		grantRepository.findByGrantee(caller.userId()).forEach(grant -> ids.add(grant.getFolderId()));
		return ids;
	}

	/** Every folder {@code caller} may view, with the caller's rights on each. */
	public Snapshot snapshot(Caller caller) {
		Map<UUID, MediaFolderAccessLevel> levels = new HashMap<>();
		grantRepository.findByGrantee(caller.userId())
			.forEach(grant -> levels.put(grant.getFolderId(), grant.getAccessLevel()));
		List<MediaFolder> candidates;
		if (caller.tenantWide()) {
			candidates = folderRepository.findAll();
		}
		else {
			Map<UUID, MediaFolder> union = new LinkedHashMap<>();
			folderRepository.findOwnedBy(caller.userId()).forEach(folder -> union.put(folder.getId(), folder));
			folderRepository.findByIds(levels.keySet()).forEach(folder -> union.putIfAbsent(folder.getId(), folder));
			candidates = new ArrayList<>(union.values());
		}
		Map<UUID, MediaFolder> viewable = new LinkedHashMap<>();
		Map<UUID, FolderRights> rights = new HashMap<>();
		for (MediaFolder folder : candidates) {
			FolderRights folderRights = rights(caller, folder, levels.get(folder.getId()));
			if (folderRights.canView()) {
				viewable.put(folder.getId(), folder);
				rights.put(folder.getId(), folderRights);
			}
		}
		return new Snapshot(viewable, rights);
	}

	public static NotFoundException folderNotFound() {
		return new NotFoundException("Media folder not found");
	}

	/**
	 * @param tenantWide non-Teacher/TA staff holding Manage or Share: sees every folder
	 * @param addMedia holds library CREATE_EDIT (Add Media)
	 */
	public record Caller(UUID userId, boolean tenantWide, boolean holdsManage, boolean holdsShare, boolean addMedia) {

	}

	/** {@code relation} {@code null} = no access (and every flag false). */
	public record FolderRights(MediaFolderRelation relation, boolean canView, boolean canUpload, boolean canManage,
			boolean canShare, boolean canTransfer) {

		public static final FolderRights NONE = new FolderRights(null, false, false, false, false, false);

	}

	/** The folders one caller may view (tree disclosure helpers, MEDIA-FOLDERS-01 section 4). */
	public static final class Snapshot {

		private static final Comparator<MediaFolder> BY_NAME = Comparator
			.comparing((MediaFolder folder) -> folder.getName().toLowerCase(Locale.ROOT))
			.thenComparing(MediaFolder::getId);

		private final Map<UUID, MediaFolder> viewable;

		private final Map<UUID, FolderRights> rights;

		Snapshot(Map<UUID, MediaFolder> viewable, Map<UUID, FolderRights> rights) {
			this.viewable = viewable;
			this.rights = rights;
		}

		public boolean isViewable(UUID folderId) {
			return folderId != null && viewable.containsKey(folderId);
		}

		public FolderRights rights(UUID folderId) {
			return rights.getOrDefault(folderId, FolderRights.NONE);
		}

		/** Root disclosure: viewable folders whose parent is NULL or not viewable, by name. */
		public List<MediaFolder> roots() {
			return viewable.values().stream().filter(folder -> !isViewable(folder.getParentId())).sorted(BY_NAME).toList();
		}

		/** Viewable children of {@code parentId}, by name. */
		public List<MediaFolder> children(UUID parentId) {
			return viewable.values()
				.stream()
				.filter(folder -> parentId.equals(folder.getParentId()))
				.sorted(BY_NAME)
				.toList();
		}

		public int viewableChildCount(UUID parentId) {
			return (int) viewable.values().stream().filter(folder -> parentId.equals(folder.getParentId())).count();
		}

		/** The parent id when the caller may view the parent; otherwise {@code null} (never disclosed). */
		public UUID disclosedParentId(MediaFolder folder) {
			return isViewable(folder.getParentId()) ? folder.getParentId() : null;
		}

		/** Contiguous viewable ancestors directly above {@code folder}, root-most first. */
		public List<MediaFolder> breadcrumb(MediaFolder folder) {
			List<MediaFolder> chain = new ArrayList<>();
			Set<UUID> seen = new HashSet<>();
			seen.add(folder.getId());
			UUID parentId = folder.getParentId();
			while (isViewable(parentId) && seen.add(parentId)) {
				MediaFolder parent = viewable.get(parentId);
				chain.add(parent);
				parentId = parent.getParentId();
			}
			Collections.reverse(chain);
			return chain;
		}

	}

}
