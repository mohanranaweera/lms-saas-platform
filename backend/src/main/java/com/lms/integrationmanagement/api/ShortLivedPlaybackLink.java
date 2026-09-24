package com.lms.integrationmanagement.api;

import java.time.Instant;

/**
 * A freshly-minted, short-lived recording-playback URL - never persisted
 * anywhere (see {@link LiveClassProviderApi#getRecordingPlaybackUrl}'s
 * javadoc).
 */
public record ShortLivedPlaybackLink(String url, Instant expiresAt) {

}
