package com.lms.videoaccessmanagement.domain;

/**
 * How an asset is played back - a capability of the hosting provider, never
 * a course or lesson type. Which modes are actually usable is decided by
 * {@code VideoProviderCatalog} (provider support) intersected with {@code
 * VideoFeatureFlags} (platform switches).
 */
public enum PlaybackMode {

	/** Existing ADR-008 path: short-lived signed object-storage URL. */
	INTERNAL_SIGNED_URL,

	/** YouTube IFrame Player API with documented parameters only. */
	YOUTUBE_STANDARD,

	/**
	 * Reserved extension point (VID-ADR-010). No resolver exists and the flag
	 * cannot be enabled until an authorized provider arrangement and its ADR
	 * exist.
	 */
	YOUTUBE_CUSTOM,

	/** Signed HLS/DASH from a managed streaming provider (Phase 13). */
	MANAGED_STREAM,

	/** Signed HLS/DASH from tenant/platform-managed external storage + CDN (interface only). */
	SELF_HOSTED

}
