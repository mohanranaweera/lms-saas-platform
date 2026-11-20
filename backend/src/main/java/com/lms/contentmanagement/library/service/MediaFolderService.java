package com.lms.contentmanagement.library.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.ProviderConflictException;
import com.lms.common.tenant.TenantContext;
import com.lms.contentmanagement.library.domain.MediaFile;
import com.lms.contentmanagement.library.domain.MediaFolder;
import com.lms.contentmanagement.library.domain.MediaFolderAccessLevel;
import com.lms.contentmanagement.library.domain.MediaFolderGrant;
import com.lms.contentmanagement.library.repository.MediaFileRepository;
import com.lms.contentmanagement.library.repository.MediaFolderGrantRepository;
import com.lms.contentmanagement.library.repository.MediaFolderRepository;
import com.lms.contentmanagement.library.service.MediaFolderViews.Crumb;
import com.lms.contentmanagement.library.service.MediaFolderViews.Detail;
import com.lms.contentmanagement.library.service.MediaFolderViews.Folder;
import com.lms.contentmanagement.library.service.MediaFolderViews.Grant;
import com.lms.contentmanagement.library.service.MediaFolderViews.Member;
import com.lms.contentmanagement.library.support.MediaFileAccessGuard;
import com.lms.contentmanagement.library.support.MediaFolderAccess;
import com.lms.contentmanagement.library.support.MediaFolderAccess.Caller;
import com.lms.contentmanagement.library.support.MediaFolderAccess.FolderRights;
import com.lms.contentmanagement.library.support.MediaFolderAccess.Snapshot;
import com.lms.contentmanagement.library.support.MediaUserNames;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.usermanagement.api.TeacherLookupApi.TeacherSummary;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Media Library folders (MEDIA-FOLDERS-01): tree, grants and ownership transfer for the Uploads
 * tab. Authorization: library VIEW ({@link MediaFileAccessGuard}) then the folder policy ({@link
 * MediaFolderAccess}) - an invisible folder is 404, a visible one without the right is 403.
 *
 * <p><b>Concurrency</b> (section 5): every mutation first takes the tenant's media-folders
 * advisory lock, then re-reads and re-checks inside the same transaction (cycle check,
 * emptiness, sibling-name uniqueness - the unique index is the final guard - and the optional
 * {@code expectedVersion}). <b>Audit</b> rows are written in the same transaction.
 */
@Service
public class MediaFolderService {

	/** Former owner's retained access after an ownership transfer. */
	public enum FormerOwnerAccess {

		NONE, VIEW, UPLOAD

	}

	static final int MAX_GRANTEES_PER_REQUEST = 50;

	private static final String TARGET_ENTITY = "media_folder";

	private final MediaFolderRepository folderRepository;

	private final MediaFolderGrantRepository grantRepository;

	private final MediaFileRepository fileRepository;

	private final MediaFileAccessGuard accessGuard;

	private final MediaFolderAccess folderAccess;

	private final MediaUserNames userNames;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public MediaFolderService(MediaFolderRepository folderRepository, MediaFolderGrantRepository grantRepository,
			MediaFileRepository fileRepository, MediaFileAccessGuard accessGuard, MediaFolderAccess folderAccess,
			MediaUserNames userNames, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.folderRepository = folderRepository;
		this.grantRepository = grantRepository;
		this.fileRepository = fileRepository;
		this.accessGuard = accessGuard;
		this.folderAccess = folderAccess;
		this.userNames = userNames;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	// ------------------------------------------------------------------
	// Reads.
	// ------------------------------------------------------------------

	/** Root disclosure ({@code parentId} null) or the viewable children of a viewable folder. */
	@Transactional(readOnly = true)
	public List<Folder> list(UUID parentId) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		Snapshot snapshot = folderAccess.snapshot(caller);
		List<MediaFolder> folders;
		if (parentId == null) {
			folders = snapshot.roots();
		}
		else {
			if (!snapshot.isViewable(parentId)) {
				throw MediaFolderAccess.folderNotFound();
			}
			folders = snapshot.children(parentId);
		}
		return toViews(caller, snapshot, folders);
	}

	@Transactional(readOnly = true)
	public Detail get(UUID id) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		Snapshot snapshot = folderAccess.snapshot(caller);
		if (!snapshot.isViewable(id)) {
			throw MediaFolderAccess.folderNotFound();
		}
		MediaFolder folder = folderRepository.findById(id).orElseThrow(MediaFolderAccess::folderNotFound);
		List<Crumb> breadcrumb = snapshot.breadcrumb(folder)
			.stream()
			.map(ancestor -> new Crumb(ancestor.getId(), ancestor.getName()))
			.toList();
		return new Detail(toViews(caller, snapshot, List.of(folder)).get(0), breadcrumb);
	}

	// ------------------------------------------------------------------
	// Tree mutations (Manage).
	// ------------------------------------------------------------------

	@Transactional
	public Folder create(String rawName, UUID parentId) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		if (!caller.holdsManage()) {
			throw forbidden();
		}
		String name = validateName(rawName);
		folderAccess.lockTree();
		if (parentId != null) {
			MediaFolder parent = folderAccess.requireViewable(caller, parentId);
			if (!folderAccess.rights(caller, parent).canManage()) {
				throw forbidden();
			}
		}
		requireUniqueName(parentId, name, null);
		MediaFolder folder = saveAndFlush(new MediaFolder(tenantContext.getTenantId(), parentId, name, caller.userId()));
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", name);
		putIfPresent(metadata, "parentId", parentId);
		audit(caller, "media_folder.created", folder.getId(), metadata);
		return view(caller, folder);
	}

	@Transactional
	public Folder rename(UUID id, String rawName, Long expectedVersion) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		String name = validateName(rawName);
		folderAccess.lockTree();
		MediaFolder folder = requireManageable(caller, id);
		requireVersion(folder, expectedVersion);
		String previous = folder.getName();
		if (!previous.equals(name)) {
			requireUniqueName(folder.getParentId(), name, folder.getId());
			folder.rename(name);
			saveAndFlush(folder);
			audit(caller, "media_folder.renamed", folder.getId(), Map.of("from", previous, "to", name));
		}
		return view(caller, folder);
	}

	/** Moves {@code id} under {@code newParentId} ({@code null} = root). Never into itself or a descendant. */
	@Transactional
	public Folder move(UUID id, UUID newParentId, Long expectedVersion) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		folderAccess.lockTree();
		MediaFolder folder = requireManageable(caller, id);
		requireVersion(folder, expectedVersion);
		UUID previousParentId = folder.getParentId();
		if (Objects.equals(previousParentId, newParentId)) {
			return view(caller, folder);
		}
		if (newParentId != null) {
			if (newParentId.equals(id)) {
				throw invalidMove();
			}
			MediaFolder destination = folderAccess.requireViewable(caller, newParentId);
			if (isSelfOrDescendant(destination, id)) {
				throw invalidMove();
			}
			if (!folderAccess.rights(caller, destination).canManage()) {
				throw forbidden();
			}
		}
		requireUniqueName(newParentId, folder.getName(), folder.getId());
		folder.moveTo(newParentId);
		saveAndFlush(folder);
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", folder.getName());
		putIfPresent(metadata, "fromParentId", previousParentId);
		putIfPresent(metadata, "toParentId", newParentId);
		audit(caller, "media_folder.moved", folder.getId(), metadata);
		return view(caller, folder);
	}

	/**
	 * Hard delete of an EMPTY folder: any child folder or live file - visible to the caller or
	 * not - refuses with 409 {@code MEDIA_FOLDER_NOT_EMPTY} (no counts disclosed). Soft-deleted
	 * files still pointing at it are moved to Unfiled; its grants go with it.
	 */
	@Transactional
	public void delete(UUID id, Long expectedVersion) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		folderAccess.lockTree();
		MediaFolder folder = requireManageable(caller, id);
		requireVersion(folder, expectedVersion);
		if (folderRepository.existsChild(id) || fileRepository.existsLiveInFolder(id)) {
			throw new ProviderConflictException(ApiErrorCodes.MEDIA_FOLDER_NOT_EMPTY,
					"Only an empty folder can be deleted. Move or delete its contents first.");
		}
		for (MediaFile deletedFile : fileRepository.findDeletedInFolder(id)) {
			deletedFile.moveToFolder(null);
			fileRepository.save(deletedFile);
		}
		grantRepository.deleteAll(grantRepository.findByFolderId(id));
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", folder.getName());
		putIfPresent(metadata, "parentId", folder.getParentId());
		metadata.put("ownerUserId", folder.getOwnerUserId().toString());
		folderRepository.delete(folder);
		folderRepository.flush();
		audit(caller, "media_folder.deleted", id, metadata);
	}

	// ------------------------------------------------------------------
	// Grants (Share).
	// ------------------------------------------------------------------

	@Transactional(readOnly = true)
	public List<Grant> listGrants(UUID id) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		MediaFolder folder = requireShareable(caller, id);
		return grantViews(folder);
	}

	/** Adds or changes grants (upsert) for approved active teachers other than the owner. */
	@Transactional
	public List<Grant> upsertGrants(UUID id, List<UUID> userIds, MediaFolderAccessLevel requestedLevel) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		MediaFolderAccessLevel level = requestedLevel == null ? MediaFolderAccessLevel.VIEW : requestedLevel;
		Set<UUID> grantees = validateGrantees(userIds);
		folderAccess.lockTree();
		MediaFolder folder = requireShareable(caller, id);
		Set<UUID> eligible = eligibleIds(folder);
		if (!eligible.containsAll(grantees)) {
			throw new FieldValidationException("Invalid members",
					List.of(new FieldError("userIds", "Folders can be shared only with active teachers")));
		}
		Instant now = Instant.now();
		for (UUID granteeId : grantees) {
			MediaFolderGrant existing = grantRepository.findByFolderIdAndGrantee(folder.getId(), granteeId).orElse(null);
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("folderName", folder.getName());
			metadata.put("granteeUserId", granteeId.toString());
			if (existing == null) {
				grantRepository.save(new MediaFolderGrant(folder.getTenantId(), folder.getId(), granteeId, level,
						caller.userId(), now));
			}
			else if (existing.getAccessLevel() != level) {
				metadata.put("fromLevel", existing.getAccessLevel().name());
				existing.changeLevel(level, caller.userId(), now);
				grantRepository.save(existing);
			}
			else {
				continue;
			}
			metadata.put("toLevel", level.name());
			audit(caller, "media_folder.shared", folder.getId(), metadata);
		}
		grantRepository.flush();
		return grantViews(folder);
	}

	/** Removes one member's grant (no-op when there is none). Stops new open-URL issuance at once. */
	@Transactional
	public List<Grant> revokeGrant(UUID id, UUID granteeId) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		folderAccess.lockTree();
		MediaFolder folder = requireShareable(caller, id);
		grantRepository.findByFolderIdAndGrantee(folder.getId(), granteeId).ifPresent(grant -> {
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("folderName", folder.getName());
			metadata.put("granteeUserId", granteeId.toString());
			metadata.put("fromLevel", grant.getAccessLevel().name());
			grantRepository.delete(grant);
			grantRepository.flush();
			audit(caller, "media_folder.unshared", folder.getId(), metadata);
		});
		return grantViews(folder);
	}

	/** Approved active teachers the folder may be shared with / transferred to (never the current owner). */
	@Transactional(readOnly = true)
	public List<Member> eligibleMembers(UUID id) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		MediaFolder folder = requireShareable(caller, id);
		return userNames.eligibleMembers()
			.stream()
			.filter(teacher -> !teacher.userId().equals(folder.getOwnerUserId()))
			.map(teacher -> new Member(teacher.userId(), teacher.name()))
			.toList();
	}

	// ------------------------------------------------------------------
	// Ownership transfer (Manage AND Share).
	// ------------------------------------------------------------------

	/**
	 * Transfers {@code id} plus every descendant owned by the same (former) owner to {@code
	 * newOwnerId}; differently-owned subfolders keep their owner. The new owner's grants on the
	 * transferred folders are removed; {@code formerOwnerAccess} VIEW / UPLOAD upserts a grant for
	 * the former owner on each, NONE adds nothing. File uploaders, file locations and every other
	 * grant are untouched.
	 */
	@Transactional
	public Folder transfer(UUID id, UUID newOwnerId, FormerOwnerAccess formerOwnerAccess, Long expectedVersion) {
		accessGuard.requireLibraryAccess(PermissionAction.VIEW);
		Caller caller = folderAccess.caller();
		FormerOwnerAccess retention = formerOwnerAccess == null ? FormerOwnerAccess.NONE : formerOwnerAccess;
		if (newOwnerId == null) {
			throw new FieldValidationException("Invalid new owner",
					List.of(new FieldError("newOwnerId", "New owner is required")));
		}
		folderAccess.lockTree();
		MediaFolder folder = folderAccess.requireViewable(caller, id);
		if (!folderAccess.rights(caller, folder).canTransfer()) {
			throw forbidden();
		}
		requireVersion(folder, expectedVersion);
		if (!eligibleIds(folder).contains(newOwnerId)) {
			throw new FieldValidationException("Invalid new owner", List.of(new FieldError("newOwnerId",
					"The new owner must be an active teacher other than the current owner")));
		}
		UUID formerOwnerId = folder.getOwnerUserId();
		List<MediaFolder> transferred = sameOwnerSubtree(folder, formerOwnerId);
		Instant now = Instant.now();
		for (MediaFolder target : transferred) {
			target.transferTo(newOwnerId);
			folderRepository.save(target);
			grantRepository.findByFolderIdAndGrantee(target.getId(), newOwnerId).ifPresent(grantRepository::delete);
			if (retention != FormerOwnerAccess.NONE) {
				MediaFolderAccessLevel level = MediaFolderAccessLevel.valueOf(retention.name());
				MediaFolderGrant existing = grantRepository.findByFolderIdAndGrantee(target.getId(), formerOwnerId)
					.orElse(null);
				if (existing == null) {
					grantRepository.save(new MediaFolderGrant(target.getTenantId(), target.getId(), formerOwnerId, level,
							caller.userId(), now));
				}
				else {
					existing.changeLevel(level, caller.userId(), now);
					grantRepository.save(existing);
				}
			}
		}
		folderRepository.flush();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("name", folder.getName());
		metadata.put("fromOwnerUserId", formerOwnerId.toString());
		metadata.put("toOwnerUserId", newOwnerId.toString());
		metadata.put("formerOwnerAccess", retention.name());
		metadata.put("transferredFolderIds", transferred.stream().map(f -> f.getId().toString()).toList());
		audit(caller, "media_folder.ownership_transferred", folder.getId(), metadata);
		return view(caller, folder);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private MediaFolder requireManageable(Caller caller, UUID id) {
		MediaFolder folder = folderAccess.requireViewable(caller, id);
		if (!folderAccess.rights(caller, folder).canManage()) {
			throw forbidden();
		}
		return folder;
	}

	private MediaFolder requireShareable(Caller caller, UUID id) {
		MediaFolder folder = folderAccess.requireViewable(caller, id);
		if (!folderAccess.rights(caller, folder).canShare()) {
			throw forbidden();
		}
		return folder;
	}

	private Set<UUID> eligibleIds(MediaFolder folder) {
		return userNames.eligibleMembers()
			.stream()
			.map(TeacherSummary::userId)
			.filter(userId -> !userId.equals(folder.getOwnerUserId()))
			.collect(Collectors.toSet());
	}

	private static Set<UUID> validateGrantees(List<UUID> userIds) {
		if (userIds == null || userIds.isEmpty() || userIds.size() > MAX_GRANTEES_PER_REQUEST
				|| userIds.stream().anyMatch(Objects::isNull)) {
			throw new FieldValidationException("Invalid members", List.of(new FieldError("userIds",
					"Choose between 1 and " + MAX_GRANTEES_PER_REQUEST + " members")));
		}
		return new LinkedHashSet<>(userIds);
	}

	/** Walks up from {@code destination}: is {@code folderId} the destination or one of its ancestors? */
	private boolean isSelfOrDescendant(MediaFolder destination, UUID folderId) {
		Set<UUID> seen = new HashSet<>();
		MediaFolder current = destination;
		while (current != null && seen.add(current.getId())) {
			if (current.getId().equals(folderId)) {
				return true;
			}
			UUID parentId = current.getParentId();
			current = parentId == null ? null : folderRepository.findById(parentId).orElse(null);
		}
		return false;
	}

	/** {@code root} plus every descendant (through any owner) whose owner is {@code ownerId}. */
	private List<MediaFolder> sameOwnerSubtree(MediaFolder root, UUID ownerId) {
		Map<UUID, List<MediaFolder>> children = new HashMap<>();
		for (MediaFolder folder : folderRepository.findAll()) {
			if (folder.getParentId() != null) {
				children.computeIfAbsent(folder.getParentId(), key -> new ArrayList<>()).add(folder);
			}
		}
		List<MediaFolder> result = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		Deque<MediaFolder> queue = new ArrayDeque<>();
		queue.add(root);
		while (!queue.isEmpty()) {
			MediaFolder current = queue.poll();
			if (!seen.add(current.getId())) {
				continue;
			}
			if (ownerId.equals(current.getOwnerUserId())) {
				result.add(current);
			}
			queue.addAll(children.getOrDefault(current.getId(), List.of()));
		}
		return result;
	}

	private void requireUniqueName(UUID parentId, String name, UUID excludeId) {
		if (folderRepository.existsSiblingName(parentId, name, excludeId)) {
			throw nameTaken();
		}
	}

	private MediaFolder saveAndFlush(MediaFolder folder) {
		try {
			return folderRepository.saveAndFlush(folder);
		}
		catch (DataIntegrityViolationException e) {
			// uq_media_folder_sibling_name is the final guard behind requireUniqueName.
			throw nameTaken();
		}
	}

	private static void requireVersion(MediaFolder folder, Long expectedVersion) {
		if (expectedVersion != null && expectedVersion != folder.getVersion()) {
			throw new ProviderConflictException(ApiErrorCodes.MEDIA_FOLDER_STALE,
					"This folder was changed by someone else. Refresh and try again.");
		}
	}

	/** Server-authoritative name rule: stripped, 1..100 UTF-16 characters, no control characters. */
	static String validateName(String rawName) {
		String name = rawName == null ? "" : rawName.strip();
		if (name.isEmpty()) {
			throw invalidName("Name is required");
		}
		if (name.length() > MediaFolder.MAX_NAME_LENGTH) {
			throw invalidName("Name must be at most " + MediaFolder.MAX_NAME_LENGTH + " characters");
		}
		if (name.chars().anyMatch(Character::isISOControl)) {
			throw invalidName("Name must not contain control characters");
		}
		return name;
	}

	private static FieldValidationException invalidName(String message) {
		return new FieldValidationException("Invalid folder name", List.of(new FieldError("name", message)));
	}

	private static ProviderConflictException nameTaken() {
		return new ProviderConflictException(ApiErrorCodes.MEDIA_FOLDER_NAME_TAKEN,
				"A folder with this name already exists here.");
	}

	private static ProviderConflictException invalidMove() {
		return new ProviderConflictException(ApiErrorCodes.MEDIA_FOLDER_INVALID_MOVE,
				"A folder cannot be moved into itself or one of its subfolders.");
	}

	private static AccessDeniedException forbidden() {
		return new AccessDeniedException("You do not have permission to perform this action");
	}

	private static void putIfPresent(Map<String, Object> metadata, String key, UUID value) {
		if (value != null) {
			metadata.put(key, value.toString());
		}
	}

	private void audit(Caller caller, String action, UUID folderId, Map<String, Object> metadata) {
		auditLogApi.record(new AuditLogEntry(caller.userId(), action, TARGET_ENTITY, folderId, null, metadata));
	}

	private List<Grant> grantViews(MediaFolder folder) {
		List<MediaFolderGrant> grants = grantRepository.findByFolderId(folder.getId());
		Map<UUID, String> names = userNames.names(grants.stream().map(MediaFolderGrant::getGranteeUserId).toList());
		return grants.stream()
			.map(grant -> new Grant(grant.getGranteeUserId(), names.get(grant.getGranteeUserId()),
					grant.getAccessLevel(), grant.getGrantedAt()))
			.sorted(Comparator.comparing((Grant grant) -> grant.name() == null ? ""
					: grant.name().toLowerCase(Locale.ROOT)).thenComparing(Grant::userId))
			.toList();
	}

	/** One folder after a mutation (fresh snapshot, so counts and disclosure are current). */
	private Folder view(Caller caller, MediaFolder folder) {
		return toViews(caller, folderAccess.snapshot(caller), List.of(folder)).get(0);
	}

	private List<Folder> toViews(Caller caller, Snapshot snapshot, List<MediaFolder> folders) {
		if (folders.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = folders.stream().map(MediaFolder::getId).toList();
		Map<UUID, Long> fileCounts = fileRepository.findLiveFolderIdsIn(ids)
			.stream()
			.collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
		Map<UUID, String> ownerNames = userNames
			.names(folders.stream().map(MediaFolder::getOwnerUserId).collect(Collectors.toSet()));
		List<Folder> views = new ArrayList<>(folders.size());
		for (MediaFolder folder : folders) {
			FolderRights rights = snapshot.isViewable(folder.getId()) ? snapshot.rights(folder.getId())
					: folderAccess.rights(caller, folder);
			views.add(new Folder(folder.getId(), folder.getName(), snapshot.disclosedParentId(folder),
					folder.getOwnerUserId(), ownerNames.get(folder.getOwnerUserId()), rights.relation(),
					rights.canManage(), rights.canShare(), rights.canTransfer(), rights.canUpload(),
					snapshot.viewableChildCount(folder.getId()), fileCounts.getOrDefault(folder.getId(), 0L),
					folder.getVersion(), folder.getCreatedAt(), folder.getUpdatedAt()));
		}
		return views;
	}

}
