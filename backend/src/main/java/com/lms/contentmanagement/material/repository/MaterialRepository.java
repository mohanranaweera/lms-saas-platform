package com.lms.contentmanagement.material.repository;

import com.lms.common.persistence.CrossTenantPersistenceException;
import com.lms.common.persistence.TenantAwareRepository;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.contentmanagement.material.domain.Material;
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

	default int findMaxSequenceByLessonId(UUID lessonId) {
		return findByLessonId(lessonId).stream().mapToInt(Material::getSequence).max().orElse(0);
	}

	/**
	 * Reverse lookup used by {@code MaterialLookupApi} ({@code
	 * VideoAccessGuard}'s entitlement resolution, plan §3/§9's {@code
	 * idx_material_tenant_video_asset} index) - "which Material references
	 * this video asset". Structurally tenant-scoped like every other {@code
	 * default} finder here (the inherited {@code Specification}-backed
	 * {@code findOne}).
	 */
	default Optional<Material> findByVideoAssetId(UUID videoAssetId) {
		return findOne((root, query, cb) -> cb.equal(root.get("videoAssetId"), videoAssetId));
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
