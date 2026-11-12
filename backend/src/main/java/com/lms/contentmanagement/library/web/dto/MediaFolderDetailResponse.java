package com.lms.contentmanagement.library.web.dto;

import com.lms.contentmanagement.library.support.MediaFolderRelation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code MediaFolderDetail} = {@link MediaFolderResponse} plus {@code breadcrumb}: the contiguous
 * chain of ancestors the caller may view directly above the folder, root-most first (the folder
 * itself excluded; it stops at the first hidden ancestor).
 */
public record MediaFolderDetailResponse(UUID id, String name, UUID parentId, UUID ownerUserId, String ownerName,
		MediaFolderRelation access, boolean canManage, boolean canShare, boolean canTransfer, boolean canUpload,
		int childFolderCount, long fileCount, long version, Instant createdAt, Instant updatedAt,
		List<Crumb> breadcrumb) {

	public record Crumb(UUID id, String name) {

	}

}
