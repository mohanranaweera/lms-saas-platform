package com.lms.contentmanagement.api;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The single, narrow read {@code video-access-management} is permitted to
 * depend on from {@code content-management} (Wave 5, plan §3/§9) - mirrors
 * {@code coursemanagement.api.CourseLookupApi#resolveLessonOwnership}'s exact
 * contract shape for a different owning aggregate. {@code VideoAccessGuard}
 * calls this to resolve "which course does this video asset belong to" from
 * the {@code material} row that references it (a video asset is reached
 * only via a {@code Material.video_asset_id}, plan §3).
 *
 * <p>Every method resolves tenant identity exclusively from {@link
 * com.lms.common.tenant.TenantContext} - there is no overload that accepts a
 * caller-supplied tenant id.
 */
public interface MaterialLookupApi {

	/**
	 * @return the resolved course-ownership context for the video asset, or
	 * {@link Optional#empty()} if no {@code material} row in the caller's
	 * tenant currently references {@code videoAssetId} (including a
	 * cross-tenant id - never distinguished from "not yet attached to any
	 * material").
	 */
	Optional<MaterialVideoOwnership> resolveVideoAssetOwnership(UUID videoAssetId);

	/**
	 * VIDEO Phase 5: which of {@code videoAssetIds} a material of the
	 * caller's tenant references (the Media Library "attached" flag and the
	 * delete guard). Ids of other tenants are never in the result.
	 */
	Set<UUID> findVideoAssetIdsInUse(Collection<UUID> videoAssetIds);

	/**
	 * VIDEO Phase 6: attachment count per video asset ID in the caller's tenant.
	 */
	java.util.Map<UUID, Integer> countLessonAttachments(Collection<UUID> videoAssetIds);

	/**
	 * VIDEO Phase 6 (Q-010): video asset IDs attached to any lesson in courses
	 * taught by {@code teacherUserId} in the caller's tenant.
	 */
	Set<UUID> findVideoAssetIdsForTeacher(UUID teacherUserId);

	/** VIDEO Phase 7: resolves one LessonMedia row for playback authorization. */
	Optional<LessonMediaPlaybackContext> resolveLessonMediaForPlayback(UUID lessonMediaId);

}
