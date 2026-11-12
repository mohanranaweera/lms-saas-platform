package com.lms.contentmanagement.library.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.contentmanagement.library.domain.MediaFolder;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped per ADR-006 (V122 {@code media_folder}): every finder is a {@link
 * Specification}-backed default method, so {@code TenantAwareRepositoryImpl} ANDs in the tenant
 * predicate from the resolved {@code TenantContext}. No method accepts a tenant id.
 */
public interface MediaFolderRepository extends TenantAwareRepository<MediaFolder, UUID> {

	default List<MediaFolder> findOwnedBy(UUID ownerUserId) {
		return findAll((root, query, cb) -> cb.equal(root.get("ownerUserId"), ownerUserId));
	}

	default List<MediaFolder> findByIds(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> root.get("id").in(ids));
	}

	/** Any child folder of {@code parentId} (visible to the caller or not). */
	default boolean existsChild(UUID parentId) {
		return exists((root, query, cb) -> cb.equal(root.get("parentId"), parentId));
	}

	/**
	 * Is {@code name} already used (case-insensitively, like {@code uq_media_folder_sibling_name})
	 * by a sibling under {@code parentId} ({@code null} = root), other than {@code excludeId}?
	 */
	default boolean existsSiblingName(UUID parentId, String name, UUID excludeId) {
		Specification<MediaFolder> spec = (root, query, cb) -> cb.equal(cb.lower(root.get("name")),
				cb.lower(cb.literal(name)));
		spec = spec.and((root, query, cb) -> parentId == null ? cb.isNull(root.get("parentId"))
				: cb.equal(root.get("parentId"), parentId));
		if (excludeId != null) {
			spec = spec.and((root, query, cb) -> cb.notEqual(root.get("id"), excludeId));
		}
		return exists(spec);
	}

	/**
	 * Transaction-scoped Postgres advisory lock (released at commit / rollback) on a key the
	 * service builds from the resolved tenant ({@code "media-folders:" + tenantId}). Serializes
	 * every folder-tree / grant mutation, file move and upload into a folder of one tenant.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireTreeLock(@Param("lockKey") String lockKey);

}
