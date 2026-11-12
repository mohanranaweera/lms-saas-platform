package com.lms.contentmanagement.library.web.dto;

import com.lms.contentmanagement.library.support.MediaFolderRelation;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code MediaFolderSummary} (MEDIA-FOLDERS-01 section 6). {@code parentId} is {@code null} at root
 * and whenever the caller may not view the parent; {@code access} is the caller's relation;
 * {@code childFolderCount} counts only child folders the caller may view. Every {@code can*}
 * flag is computed server-side for the caller.
 */
public record MediaFolderResponse(UUID id, String name, UUID parentId, UUID ownerUserId, String ownerName,
		MediaFolderRelation access, boolean canManage, boolean canShare, boolean canTransfer, boolean canUpload,
		int childFolderCount, long fileCount, long version, Instant createdAt, Instant updatedAt) {

}
