package com.lms.contentmanagement.library.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.contentmanagement.library.domain.MediaFile;
import com.lms.contentmanagement.library.domain.MediaFileKind;
import com.lms.contentmanagement.library.domain.MediaFileUsage;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Tenant-scoped per ADR-006: every finder is a {@link Specification}-backed
 * default method, so {@code TenantAwareRepositoryImpl} ANDs in the tenant
 * predicate from the resolved {@code TenantContext}. No method accepts a
 * tenant id. "Live" = not soft-deleted.
 */
public interface MediaFileRepository extends TenantAwareRepository<MediaFile, UUID> {

	default Optional<MediaFile> findLiveById(UUID id) {
		return findOne(byId(id).and(live()));
	}

	/** A live Media Library file (COURSE_LIBRARY); site files such as teacher photos are "not found" here. */
	default Optional<MediaFile> findLiveLibraryById(UUID id) {
		return findOne(byId(id).and(live()).and(library()));
	}

	/** Live files of one usage area among {@code ids} (tenant-scoped; unknown / other-tenant ids are absent). */
	default java.util.List<MediaFile> findLiveByIdsAndUsage(java.util.Collection<UUID> ids, MediaFileUsage usage) {
		if (ids.isEmpty()) {
			return java.util.List.of();
		}
		return findAll(live().and((root, query, cb) -> cb.and(root.get("id").in(ids),
				cb.equal(root.get("usageArea"), usage))));
	}

	/**
	 * Live library files matching {@code kind} / {@code search} inside {@code scope} (see {@link
	 * #scope}: folder filter, uploader filter and the caller's folder visibility).
	 */
	default Page<MediaFile> findLive(MediaFileKind kind, String search, Specification<MediaFile> scope,
			Pageable pageable) {
		Specification<MediaFile> spec = live().and(library()).and(scope);
		if (kind != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("kind"), kind));
		}
		if (search != null && !search.isBlank()) {
			String pattern = "%" + escapeLike(search.strip().toLowerCase(Locale.ROOT)) + "%";
			spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("title")), pattern, '\\'),
					cb.like(cb.lower(root.get("originalFilename")), pattern, '\\')));
		}
		return findAll(spec, pageable);
	}

	default long countLiveByKind(MediaFileKind kind, Specification<MediaFile> scope) {
		return count(live().and(library()).and(scope).and((root, query, cb) -> cb.equal(root.get("kind"), kind)));
	}

	/** Uploader ids of the live library files in {@code scope} (one per file; the caller de-duplicates). */
	default java.util.List<UUID> findLiveUploaderIds(Specification<MediaFile> scope) {
		return findAttributeValues(live().and(library()).and(scope), "uploadedBy", UUID.class);
	}

	/** {@code folder_id} of every live library file filed in one of {@code folderIds} (per-folder counts). */
	default java.util.List<UUID> findLiveFolderIdsIn(java.util.Collection<UUID> folderIds) {
		if (folderIds.isEmpty()) {
			return java.util.List.of();
		}
		return findAttributeValues(live().and(library()).and((root, query, cb) -> root.get("folderId").in(folderIds)),
				"folderId", UUID.class);
	}

	/** Any live file (any usage, visible to the caller or not) filed in {@code folderId}: blocks folder delete. */
	default boolean existsLiveInFolder(UUID folderId) {
		return exists(live().and((root, query, cb) -> cb.equal(root.get("folderId"), folderId)));
	}

	/** Soft-deleted files still pointing at {@code folderId} (cleared to Unfiled when the folder is deleted). */
	default java.util.List<MediaFile> findDeletedInFolder(UUID folderId) {
		return findAll((root, query, cb) -> cb.and(cb.isNotNull(root.get("deletedAt")),
				cb.equal(root.get("folderId"), folderId)));
	}

	/**
	 * Folder scope of a library read (MEDIA-FOLDERS-01 section 6):
	 * <ul>
	 * <li>{@code folderId} set: that folder only (the caller has already checked it may view it);</li>
	 * <li>{@code unfiled}: files with no folder only;</li>
	 * <li>neither: every file the caller may view - Unfiled plus files in {@code
	 * visibleFolderIds} ({@code null} = every folder, i.e. tenant-wide folder authority).</li>
	 * </ul>
	 * {@code uploadedBy}, when set, narrows to one uploader.
	 */
	static Specification<MediaFile> scope(UUID folderId, boolean unfiled, UUID uploadedBy,
			java.util.Collection<UUID> visibleFolderIds) {
		Specification<MediaFile> spec;
		if (folderId != null) {
			spec = (root, query, cb) -> cb.equal(root.get("folderId"), folderId);
		}
		else if (unfiled) {
			spec = (root, query, cb) -> cb.isNull(root.get("folderId"));
		}
		else if (visibleFolderIds == null) {
			spec = (root, query, cb) -> cb.conjunction();
		}
		else if (visibleFolderIds.isEmpty()) {
			spec = (root, query, cb) -> cb.isNull(root.get("folderId"));
		}
		else {
			spec = (root, query, cb) -> cb.or(cb.isNull(root.get("folderId")),
					root.get("folderId").in(visibleFolderIds));
		}
		if (uploadedBy != null) {
			spec = spec.and((root, query, cb) -> cb.equal(root.get("uploadedBy"), uploadedBy));
		}
		return spec;
	}

	private static Specification<MediaFile> byId(UUID id) {
		return (root, query, cb) -> cb.equal(root.get("id"), id);
	}

	private static Specification<MediaFile> library() {
		return (root, query, cb) -> cb.equal(root.get("usageArea"), MediaFileUsage.COURSE_LIBRARY);
	}

	private static Specification<MediaFile> live() {
		return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
	}

	private static String escapeLike(String raw) {
		return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
