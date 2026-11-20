package com.lms.tenantmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.tenantmanagement.api.TenantAssetType;
import com.lms.tenantmanagement.domain.TenantAsset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped (ADR-006 structural filter) reads of the current tenant's
 * role assets. No method accepts a caller-supplied tenant id.
 */
public interface TenantAssetRepository extends TenantAwareRepository<TenantAsset, UUID> {

	default Optional<TenantAsset> findLiveById(UUID id) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("id"), id), cb.isNull(root.get("deletedAt"))));
	}

	/**
	 * The oldest live asset of {@code type}. For singleton types (logo, favicon,
	 * receipt logo) that is the only one; for gallery types (hero banners) it is
	 * the first of possibly several - never an "incorrect result size" error.
	 */
	default Optional<TenantAsset> findLiveByType(TenantAssetType type) {
		return findAllLiveByType(type).stream().findFirst();
	}

	/** Every live asset of {@code type}, oldest first (ties broken by id). */
	default List<TenantAsset> findAllLiveByType(TenantAssetType type) {
		return findAll(liveOfType(type), Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
	}

	default long countLiveByType(TenantAssetType type) {
		return count(liveOfType(type));
	}

	private static Specification<TenantAsset> liveOfType(TenantAssetType type) {
		return (root, query, cb) -> cb.and(cb.equal(root.get("assetType"), type), cb.isNull(root.get("deletedAt")));
	}

	/**
	 * Transaction-scoped Postgres advisory lock on a caller-built key that
	 * already contains the context tenant ({@code tenant-asset:<tenant>:<type>}),
	 * serializing the per-type replace / gallery-cap check of concurrent uploads.
	 */
	@Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))", nativeQuery = true)
	void acquireLock(@Param("lockKey") String lockKey);

}
