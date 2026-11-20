package com.lms.integrationmanagement.api;

import java.time.Instant;
import java.util.Optional;

/**
 * Short-lived playback URLs for videos hosted by the institute's managed
 * streaming account (VIDEO Phase 13, ADR-026 - DynTube today).
 *
 * <p>The URL is computed locally from the account's token security key,
 * which never leaves this module: the provider is not called on the play
 * path (its API is rate-limited). The tenant comes from the request's
 * {@code TenantContext}; only the tenant's own current ACTIVE account is
 * ever used, so a disconnected account stops playback.
 */
public interface ManagedStreamPlaybackApi {

	/**
	 * A tokenized HLS playlist URL for {@code playbackKey}, valid until
	 * {@code expiresAt}. Empty when the tenant has no usable account (none,
	 * not ACTIVE, or no token security key configured).
	 *
	 * @param playbackKey the provider's playback key stored at sync time;
	 * rejected unless it matches the provider's key format
	 */
	Optional<String> signHlsUrl(MediaProvider provider, String playbackKey, Instant expiresAt);

	/**
	 * VIDEO Phase 13c: whether the tenant's current account for {@code provider}
	 * is ACTIVE. Embedded playback (ADR-026 Amendment 1) needs no token key, but a
	 * disconnected or reconnect-pending account still stops playback.
	 */
	boolean hasActiveAccount(MediaProvider provider);

}
