package com.lms.contentmanagement.material.repository;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.contentmanagement.material.domain.Material;
import com.lms.contentmanagement.material.domain.MaterialType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Tenant-scoped per ADR-006, mirroring coursemanagement's repository style exactly. */
public interface MaterialRepository extends TenantAwareRepository<Material, UUID> {

	default List<Material> findByLessonId(UUID lessonId) {
		return findAll((root, query, cb) -> cb.equal(root.get("lessonId"), lessonId),
				Sort.by(Sort.Direction.ASC, "sequence"));
	}

	default Optional<Material> findByIdAndLessonId(UUID id, UUID lessonId) {
		return findOne(
				(root, query, cb) -> cb.and(cb.equal(root.get("id"), id), cb.equal(root.get("lessonId"), lessonId)));
	}

	default boolean existsByLessonIdAndSequenceAndIdNot(UUID lessonId, Integer sequence, UUID excludedId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("lessonId"), lessonId),
				cb.equal(root.get("sequence"), sequence), cb.notEqual(root.get("id"), excludedId)));
	}

	default boolean existsByLessonIdAndSequence(UUID lessonId, Integer sequence) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("lessonId"), lessonId),
				cb.equal(root.get("sequence"), sequence)));
	}

	default java.util.Optional<Material> findVideoByLessonIdAndVideoAssetId(UUID lessonId, UUID videoAssetId) {
		return findOne((root, query, cb) -> cb.and(cb.equal(root.get("lessonId"), lessonId),
				cb.equal(root.get("videoAssetId"), videoAssetId),
				root.get("materialType").in(MaterialType.VIDEO, MaterialType.RECORDING)));
	}

	default List<Material> findVideoMaterialsByLessonId(UUID lessonId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("lessonId"), lessonId),
				root.get("materialType").in(MaterialType.VIDEO, MaterialType.RECORDING)),
				Sort.by(Sort.Direction.ASC, "sequence"));
	}

	default int findMaxVideoSequenceByLessonId(UUID lessonId) {
		return findVideoMaterialsByLessonId(lessonId).stream().mapToInt(Material::getSequence).max().orElse(0);
	}

	default boolean existsByLessonIdAndSequenceAndIdNotForVideo(UUID lessonId, Integer sequence, UUID excludedId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("lessonId"), lessonId),
				cb.equal(root.get("sequence"), sequence), cb.notEqual(root.get("id"), excludedId),
				root.get("materialType").in(MaterialType.VIDEO, MaterialType.RECORDING)));
	}

	default int findMaxSequenceByLessonId(UUID lessonId) {
		return findByLessonId(lessonId).stream().mapToInt(Material::getSequence).max().orElse(0);
	}

	/**
	 * Reverse lookup used by {@code MaterialLookupApi} ({@code
	 * VideoAccessGuard}'s entitlement resolution, plan §3/§9's {@code
	 * idx_material_tenant_video_asset} index) - "which Material references
	 * this video asset". Structurally tenant-scoped like every other {@code
	 * default} finder here (the inherited {@code Specification}-backed
	 * {@code findAll}). KLASS-T8b SEC L3: a Media Library asset may be
	 * attached to several lessons, so this returns EVERY referencing material
	 * (ordered by id) - never a single-result {@code findOne}, which would fail
	 * (or silently pick one attachment) for a shared asset.
	 */
	default List<Material> findAllByVideoAssetId(UUID videoAssetId) {
		return findAll((root, query, cb) -> cb.equal(root.get("videoAssetId"), videoAssetId)).stream()
			.sorted(java.util.Comparator.comparing(Material::getId))
			.toList();
	}

	/** VIDEO Phase 5: materials referencing any of {@code videoAssetIds} (tenant-scoped). */
	default List<Material> findByVideoAssetIds(Collection<UUID> videoAssetIds) {
		if (videoAssetIds.isEmpty()) {
			return List.of();
		}
		return findAll((root, query, cb) -> cb.and(root.get("videoAssetId").in(videoAssetIds),
				root.get("materialType").in(MaterialType.VIDEO, MaterialType.RECORDING)));
	}

	/** VIDEO Phase 6: all video asset IDs attached to any of {@code lessonIds} in this tenant. */
	default java.util.Set<UUID> findVideoAssetIdsByLessonIds(Collection<UUID> lessonIds) {
		if (lessonIds.isEmpty()) {
			return java.util.Set.of();
		}
		return findAll((root, query, cb) -> cb.and(root.get("lessonId").in(lessonIds),
				root.get("materialType").in(MaterialType.VIDEO, MaterialType.RECORDING)))
			.stream()
			.map(Material::getVideoAssetId)
			.filter(java.util.Objects::nonNull)
			.collect(java.util.stream.Collectors.toSet());
	}

	/** VIDEO Phase 6: attachment count per video asset ID for this tenant. */
	default java.util.Map<UUID, Integer> countLessonAttachmentsByVideoAssetIds(Collection<UUID> videoAssetIds) {
		if (videoAssetIds.isEmpty()) {
			return java.util.Map.of();
		}
		java.util.Map<UUID, java.util.Set<UUID>> lessonIdsByAsset = new java.util.HashMap<>();
		for (Material material : findByVideoAssetIds(videoAssetIds)) {
			if (material.getVideoAssetId() != null && material.getLessonId() != null) {
				lessonIdsByAsset.computeIfAbsent(material.getVideoAssetId(), ignored -> new java.util.HashSet<>())
						.add(material.getLessonId());
			}
		}
		java.util.Map<UUID, Integer> counts = new java.util.HashMap<>();
		lessonIdsByAsset.forEach((assetId, lessonIds) -> counts.put(assetId, lessonIds.size()));
		return counts;
	}

	/**
	 * R2-T3: is library file {@code mediaFileId} already attached (live) to
	 * {@code lessonId}? Mirrors {@code uq_material_lesson_media_file}.
	 */
	default boolean existsByLessonIdAndMediaFileId(UUID lessonId, UUID mediaFileId) {
		return exists((root, query, cb) -> cb.and(cb.equal(root.get("lessonId"), lessonId),
				cb.equal(root.get("mediaFileId"), mediaFileId)));
	}

	/** R2-T3: live materials backed by library file {@code mediaFileId} (delete -> 409 MEDIA_ATTACHED). */
	default long countByMediaFileId(UUID mediaFileId) {
		return count((root, query, cb) -> cb.equal(root.get("mediaFileId"), mediaFileId));
	}

	/**
	 * R2-T3: number of distinct lessons each library file is attached to
	 * (live materials only), for the library's {@code attachedLessonCount}.
	 * Files with no attachment are absent from the map.
	 */
	default java.util.Map<UUID, Integer> countLessonAttachmentsByMediaFileIds(Collection<UUID> mediaFileIds) {
		if (mediaFileIds.isEmpty()) {
			return java.util.Map.of();
		}
		java.util.Map<UUID, java.util.Set<UUID>> lessonsByFile = new java.util.HashMap<>();
		for (Material material : findAll((root, query, cb) -> root.get("mediaFileId").in(mediaFileIds))) {
			if (material.getLessonId() != null) {
				lessonsByFile.computeIfAbsent(material.getMediaFileId(), ignored -> new java.util.HashSet<>())
					.add(material.getLessonId());
			}
		}
		java.util.Map<UUID, Integer> counts = new java.util.HashMap<>();
		lessonsByFile.forEach((fileId, lessons) -> counts.put(fileId, lessons.size()));
		return counts;
	}

	/**
	 * Atomic guarded {@code download_count} increment (Wave 5, PAR-06-03,
	 * plan §3/§4): {@code UPDATE ... SET download_count = download_count + 1
	 * WHERE id = :id AND tenant_id = :tenantId AND (max_downloads IS NULL OR
	 * download_count &lt; max_downloads)} - 0 affected rows means the limit
	 * was already reached (or the id/tenant didn't match), never a
	 * read-then-write race. Mirrors {@code
	 * AttendanceRecordRepository#upsertRecord}'s exact
	 * explicit-{@code tenantId}-plus-{@link #assertTenantIdMatchesContext}
	 * defense-in-depth shape, since a native/bulk {@code @Query} bypasses
	 * {@code TenantAwareRepositoryImpl}'s structural {@code Specification}
	 * filtering entirely.
	 * @return the number of rows updated (1 = incremented, 0 = limit
	 * reached/not found for this tenant).
	 *
	 * <p><b>Bug fix (Wave 5 integration testing):</b> explicitly {@code
	 * @Transactional} - unlike {@code AttendanceRecordRepository#upsertRecord},
	 * whose only caller ({@code AttendanceMarkingService#markAttendance}) is
	 * itself {@code @Transactional} and so supplies the ambient transaction
	 * this kind of default-method-delegating-to-a-native-{@code @Modifying}
	 * -query call needs, {@code MaterialService#getDownloadUrl} (this
	 * method's actual caller) is deliberately NON-transactional by design
	 * (it also calls {@code ObjectStorageApi}, an outbound dependency - see
	 * that method's own javadoc). Without this annotation, calling this
	 * default method with no ambient transaction active throws {@code
	 * InvalidDataAccessApiUsageException: No active transaction for update
	 * or delete query} - caught by this wave's own new download-limit
	 * integration tests. Annotating this single, narrow, already-atomic
	 * guarded {@code UPDATE} does not violate {@code .claude/rules/backend
	 * .md}'s "never span a transaction across an outbound call" rule - the
	 * transaction opened here is scoped to exactly this repository call and
	 * closes before {@code getDownloadUrl} ever reaches its own outbound
	 * {@code ObjectStorageApi} call.
	 */
	@Transactional
	default int incrementDownloadCountIfUnderLimit(UUID id, UUID tenantId) {
		assertTenantIdMatchesContext(tenantId);
		return incrementDownloadCountIfUnderLimitUnchecked(id, tenantId);
	}

	@Modifying
	@Query(value = "UPDATE material SET download_count = download_count + 1 "
			+ "WHERE id = :id AND tenant_id = :tenantId AND (max_downloads IS NULL OR download_count < max_downloads)",
			nativeQuery = true)
	int incrementDownloadCountIfUnderLimitUnchecked(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

	/**
	 * Defense-in-depth guard (mirrors {@code
	 * AttendanceRecordRepository#assertTenantIdMatchesContext} exactly): the
	 * passed {@code tenantId} must agree with {@link TenantContextHolder}'s
	 * own resolved value before the native update runs.
	 */
	private static void assertTenantIdMatchesContext(UUID tenantId) {
		UUID currentTenantId = new TenantContextHolder().getTenantId();
		if (!currentTenantId.equals(tenantId)) {
			throw new CrossTenantPersistenceException(
					"Attempted to update material download_count using a tenantId that does not match the current tenant context");
		}
	}

}
