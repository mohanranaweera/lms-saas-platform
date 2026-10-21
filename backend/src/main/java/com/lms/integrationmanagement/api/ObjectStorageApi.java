package com.lms.integrationmanagement.api;

import java.time.Duration;

/**
 * The narrow port other domains depend on for external object storage (e.g.
 * {@code content-management}'s lesson materials, {@code payment-management}'s
 * payment slip evidence). {@code integration-management} owns all
 * third-party credentials/webhooks - including object storage - per {@code
 * .claude/rules/architecture.md}; other domains call this {@code api}
 * interface rather than embedding a provider SDK/credentials directly.
 */
public interface ObjectStorageApi {

	StoredObject store(StoreObjectCommand command);

	void delete(String objectKey);

	SignedDownloadUrl generateSignedDownloadUrl(String objectKey, Duration ttl);

	/**
	 * Signed GET URL whose response is served by the storage origin with the
	 * given {@code Content-Type}/{@code Content-Disposition} overrides (e.g.
	 * forced {@code text/plain; charset=utf-8}, {@code inline}, a safe
	 * filename). The overrides are part of the signature, so a holder of the
	 * URL cannot change them.
	 *
	 * <p>The default implementation fails closed rather than silently
	 * ignoring the overrides (which would defeat the forced content type) -
	 * every real adapter must override it.
	 */
	default SignedDownloadUrl generateSignedDownloadUrl(String objectKey, Duration ttl, DownloadOverrides overrides) {
		throw new UnsupportedOperationException("Download response overrides are not supported by this storage adapter");
	}

}
