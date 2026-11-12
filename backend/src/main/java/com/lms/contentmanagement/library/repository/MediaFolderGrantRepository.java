package com.lms.contentmanagement.library.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.contentmanagement.library.domain.MediaFolderGrant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006 (V122 {@code media_folder_grant}); no method accepts a tenant id.
 */
public interface MediaFolderGrantRepository extends TenantAwareRepository<MediaFolderGrant, UUID> {

	default List<MediaFolderGrant> findByFolderId(UUID folderId) {
		return findAll((root, query, cb) -> cb.equal(root.get("folderId"), folderId));
	}

	default List<MediaFolderGrant> findByGrantee(UUID granteeUserId) {
		return findAll((root, query, cb) -> cb.equal(root.get("granteeUserId"), granteeUserId));
	}

	default Optional<MediaFolderGrant> findByFolderIdAndGrantee(UUID folderId, UUID granteeUserId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("folderId"), folderId),
				cb.equal(root.get("granteeUserId"), granteeUserId)));
	}

	default List<MediaFolderGrant> findByFolderIdsAndGrantee(Collection<UUID> folderIds, UUID granteeUserId) {
		if (folderIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(root.get("folderId").in(folderIds),
				cb.equal(root.get("granteeUserId"), granteeUserId)));
	}

}
