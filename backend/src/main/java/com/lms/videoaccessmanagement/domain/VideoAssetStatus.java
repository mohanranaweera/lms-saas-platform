package com.lms.videoaccessmanagement.domain;

/**
 * {@code video_asset.status} (V51, Wave 5, PAR-20-03). This wave's upload
 * path ({@code VideoAssetService#upload}) is fully synchronous (store, then
 * persist) - there is no async transcoding/probing step, so a row is only
 * ever persisted once storage has already succeeded, going straight to
 * {@link #READY}. {@link #PENDING} and {@link #FAILED} are defined to match
 * V51's {@code ck_video_asset_status} CHECK constraint and to leave room for
 * a future async pipeline (plan §11, explicitly deferred), but no code path
 * in this wave ever persists either value - see {@code VideoAssetService}'s
 * class javadoc for the full rationale.
 */
public enum VideoAssetStatus {

	PENDING, READY, FAILED

}
