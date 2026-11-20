package com.lms.videoaccessmanagement.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * {@code video_asset.status} (V51, widened by V70 - VIDEO Phase 2). The
 * provider-neutral MediaAsset processing lifecycle:
 *
 * <pre>
 * CREATED -&gt; UPLOADING -&gt; UPLOADED -&gt; PROCESSING -&gt; READY
 *    any non-terminal state -&gt; FAILED ; FAILED -&gt; UPLOADING (retry)
 *    any state except DELETED -&gt; DELETED (soft delete, terminal)
 * </pre>
 *
 * <p>Legacy {@code INTERNAL} (MinIO) uploads are still created directly as
 * {@link #READY} by {@code VideoAssetService#upload} - storage has already
 * succeeded synchronously by then. "Uploaded" never means "playable": only
 * {@link #READY} does.
 */
public enum VideoAssetStatus {

	CREATED, UPLOADING, UPLOADED, PROCESSING, READY, FAILED, DELETED;

	/** Whether {@code this -> target} is a legal transition. */
	public boolean canTransitionTo(VideoAssetStatus target) {
		return allowedTargets().contains(target);
	}

	private Set<VideoAssetStatus> allowedTargets() {
		return switch (this) {
			case CREATED -> EnumSet.of(UPLOADING, FAILED, DELETED);
			case UPLOADING -> EnumSet.of(UPLOADED, FAILED, DELETED);
			// A provider may finish processing before our first status sync.
			case UPLOADED -> EnumSet.of(PROCESSING, READY, FAILED, DELETED);
			case PROCESSING -> EnumSet.of(READY, FAILED, DELETED);
			// A READY asset can still fail later (rejected/removed at the provider).
			case READY -> EnumSet.of(FAILED, DELETED);
			case FAILED -> EnumSet.of(UPLOADING, DELETED);
			case DELETED -> EnumSet.noneOf(VideoAssetStatus.class);
		};
	}

}
