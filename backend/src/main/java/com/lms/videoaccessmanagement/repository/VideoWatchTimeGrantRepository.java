package com.lms.videoaccessmanagement.repository;

import com.lms.common.persistence.TenantAwareRepository;
import com.lms.videoaccessmanagement.domain.VideoWatchTimeGrant;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped per ADR-006. Append-only (V80 trigger): every delete method is
 * disabled, and the entity has no mutable column.
 */
public interface VideoWatchTimeGrantRepository extends TenantAwareRepository<VideoWatchTimeGrant, UUID> {

	/** One Student's grants for one video. */
	default List<VideoWatchTimeGrant> findForStudentAndAsset(UUID studentId, UUID videoAssetId) {
		return findAll((root, query, cb) -> cb.and(cb.equal(root.get("studentId"), studentId),
				cb.equal(root.get("videoAssetId"), videoAssetId)));
	}

	/** All of one Student's grants, newest first - the history shown to staff. */
	default List<VideoWatchTimeGrant> findForStudentNewestFirst(UUID studentId) {
		return findAll((root, query, cb) -> {
			query.orderBy(cb.desc(root.get("grantedAt")));
			return cb.equal(root.get("studentId"), studentId);
		});
	}

	/** Total extra seconds granted to one Student for one video. */
	default int sumExtraSeconds(UUID studentId, UUID videoAssetId) {
		return findForStudentAndAsset(studentId, videoAssetId).stream().mapToInt(VideoWatchTimeGrant::getExtraSeconds)
			.sum();
	}

	@Override
	default void deleteById(UUID id) {
		throw new UnsupportedOperationException("video_watch_time_grant is append-only");
	}

	@Override
	default void delete(VideoWatchTimeGrant entity) {
		throw new UnsupportedOperationException("video_watch_time_grant is append-only");
	}

	@Override
	default void deleteAll() {
		throw new UnsupportedOperationException("video_watch_time_grant is append-only");
	}

	@Override
	default void deleteAll(Iterable<? extends VideoWatchTimeGrant> entities) {
		throw new UnsupportedOperationException("video_watch_time_grant is append-only");
	}

	@Override
	default void deleteAllById(Iterable<? extends UUID> ids) {
		throw new UnsupportedOperationException("video_watch_time_grant is append-only");
	}

	@Override
	default void deleteAllInBatch() {
		throw new UnsupportedOperationException("video_watch_time_grant is append-only");
	}

	@Override
	default void deleteAllInBatch(Iterable<VideoWatchTimeGrant> entities) {
		throw new UnsupportedOperationException("video_watch_time_grant is append-only");
	}

	@Override
	default void deleteAllByIdInBatch(Iterable<UUID> ids) {
		throw new UnsupportedOperationException("video_watch_time_grant is append-only");
	}

}
