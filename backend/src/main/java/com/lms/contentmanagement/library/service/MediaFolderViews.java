package com.lms.contentmanagement.library.service;

import com.lms.contentmanagement.library.domain.MediaFolderAccessLevel;
import com.lms.contentmanagement.library.support.MediaFolderRelation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read models of the Media Library folder endpoints (MEDIA-FOLDERS-01 section 6). Never entities.
 */
public final class MediaFolderViews {

	private MediaFolderViews() {
	}

	/**
	 * One folder as seen by the caller.
	 *
	 * @param parentId {@code null} at root AND when the caller may not view the parent
	 * @param access the caller's relation; {@code null} only right after the caller handed the
	 * folder away (transfer with no retained access)
	 * @param childFolderCount child folders the caller may view
	 * @param fileCount live files in the folder
	 */
	public record Folder(UUID id, String name, UUID parentId, UUID ownerUserId, String ownerName,
			MediaFolderRelation access, boolean canManage, boolean canShare, boolean canTransfer, boolean canUpload,
			int childFolderCount, long fileCount, long version, Instant createdAt, Instant updatedAt) {

	}

	/** A viewable ancestor in a breadcrumb. */
	public record Crumb(UUID id, String name) {

	}

	/** A folder plus its breadcrumb (contiguous viewable ancestors, root-most first; the folder itself excluded). */
	public record Detail(Folder folder, List<Crumb> breadcrumb) {

	}

	public record Grant(UUID userId, String name, MediaFolderAccessLevel accessLevel, Instant grantedAt) {

	}

	/** A member (folder share / transfer candidate, or an uploader option). */
	public record Member(UUID userId, String name) {

	}

}
