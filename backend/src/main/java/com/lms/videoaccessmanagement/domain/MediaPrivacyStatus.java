package com.lms.videoaccessmanagement.domain;

/**
 * Provider-side visibility of an externally hosted {@link VideoAsset}
 * ({@code video_asset.privacy_status}, V70). {@code null} for
 * {@link MediaAssetProvider#INTERNAL} assets. A YouTube {@link #PRIVATE}
 * video cannot be embedded for students, so {@link #UNLISTED} is the default
 * for LMS-gated playback.
 */
public enum MediaPrivacyStatus {

	PRIVATE, UNLISTED, PUBLIC

}
