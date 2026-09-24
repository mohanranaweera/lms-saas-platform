package com.lms.integrationmanagement.api;

import java.time.Instant;

/**
 * A freshly-minted, short-lived, single-request join URL - never persisted
 * anywhere (see {@link LiveClassProviderApi#getJoinUrl}'s javadoc). Mirrors
 * {@link SignedDownloadUrl}'s shape for the equivalent video-access-management
 * precedent.
 */
public record ShortLivedJoinLink(String url, Instant expiresAt) {

}
