package com.lms.videoaccessmanagement.domain;

/**
 * What a hosting provider can do. Application logic asks these questions
 * ("can this asset carry an overlay watermark?") instead of branching on the
 * provider name.
 *
 * @param supportsOverlayWatermark whether anything may be drawn over the
 * provider's player - false for YouTube (Required Minimum Functionality)
 * @param supportsServerSideRevocation whether ending an LMS session actually
 * stops the media bytes - false for YouTube, whose video id stays playable on
 * youtube.com
 */
public record VideoProviderCapabilities(boolean supportsUpload, boolean supportsResumableUpload,
		boolean supportsExternalStorage, boolean supportsAdaptiveStreaming, boolean supportsCustomPlayer,
		boolean supportsSignedPlayback, boolean supportsDrm, boolean supportsNativeProxy,
		boolean supportsProviderPlayer, boolean supportsDelete, boolean supportsThumbnail,
		boolean supportsProcessingStatus, boolean supportsOverlayWatermark, boolean supportsServerSideRevocation) {

}
